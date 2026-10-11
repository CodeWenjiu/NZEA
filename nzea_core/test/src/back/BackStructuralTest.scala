package nzea_core.back

import nzea_core.config.{BpuConfig, BpuStrategy, BypassLevel, CoreConfig}
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.back.mem.{DbusUser, LsuData, MemPath, MemPathParameter, MmioWindow}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks of the back and its units: the back's boundary is the middle's face in and the core's two ports
  * out — the execute slot's register, the queue's face with the memory path, the dispatch and the merge are its own
  * wiring and never cross it — and each unit's own face is pinned beside it. The paths are pure units: the dispatch and
  * the merge that wrap them are the back's own; the completion path is the queue when the configuration builds one and
  * the pass-through otherwise; the forward network's column is cut to the level.
  */
object BackStructuralTest extends TestSuite:

    private val width = 32
    private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)
    private val depth = 4
    private val window = "0x10000000:0x1000"

    // ── The back ──
    private val backModule =
        Back.moduleName(
            BackParameter(
                Isa("riscv32i"),
                0x80000000L,
                RtlFlow.Yosys,
                BypassLevel.Commit,
                None,
                depth,
                Seq(window),
                StatScope.root
            )
        )

    private def lower(): String =
        TestFlow.lower(
            backModule,
            cfg =>
                Back.main(
                    Array(
                        "config",
                        cfg.toString,
                        "--isa",
                        "riscv32i",
                        "--defaultPc",
                        "2147483648",
                        "--flow",
                        "yosys",
                        "--bypass",
                        "commit",
                        "--mmioRanges",
                        window,
                        "--lsqDepth",
                        depth.toString,
                        "--stats",
                        "root"
                    )
                ),
            cfg => Back.main(Array("design", cfg.toString))
        )

    private lazy val sv = lower()

    private lazy val ports = TestFlow.ports(sv, backModule)

    /** The read's uop, in: the elastic handshake and the IS/EX register's payload — the register is this module's. */
    private def movedPorts: Map[String, Int] = Map(
        "moved_valid" -> 1,
        "moved_ready" -> 1,
        "moved_bits_pc" -> width,
        "moved_bits_next_pc" -> width,
        "moved_bits_fall_through" -> width,
        "moved_bits_pht_count" -> BpUpdate.phtCountBits,
        "moved_bits_src_0" -> width,
        "moved_bits_src_1" -> width,
        "moved_bits_src_2" -> width,
        "moved_bits_src_3" -> width,
        "moved_bits_backend" -> Backend.width,
        "moved_bits_op" -> OpWindow.width(config),
        "moved_bits_rd_index" -> Gpr.indexWidth,
        "moved_bits_csr_type" -> CsrType.width
    )

    /** One retiring record, under its own prefix: the elastic handshake and the record's fields. */
    private def retirePorts(prefix: String): Map[String, Int] = Map(
        s"${prefix}_valid" -> 1,
        s"${prefix}_ready" -> 1,
        s"${prefix}_bits_next_pc" -> width,
        s"${prefix}_bits_rd_index" -> Gpr.indexWidth,
        s"${prefix}_bits_data" -> width,
        s"${prefix}_bits_csr_type" -> CsrType.width,
        s"${prefix}_bits_csr_data" -> width,
        s"${prefix}_bits_is_mmio" -> 1,
        s"${prefix}_bits_mispredicted" -> 1
    )

    /** One offer of the register file's column: the slot's writer, then the queue's entries. */
    private def offerPorts(index: Int): Map[String, Int] = Map(
        s"forward_arf_offers_${index}_valid" -> 1,
        s"forward_arf_offers_${index}_armed" -> 1,
        s"forward_arf_offers_${index}_index" -> Gpr.indexWidth,
        s"forward_arf_offers_${index}_data" -> width
    )

    /** One offer of the CSR column: the same writers, indexed by the CSR code. */
    private def csrOfferPorts(index: Int): Map[String, Int] = Map(
        s"forward_csr_offers_${index}_valid" -> 1,
        s"forward_csr_offers_${index}_armed" -> 1,
        s"forward_csr_offers_${index}_index" -> CsrType.width,
        s"forward_csr_offers_${index}_data" -> width
    )

    /** The data bus, from the memory path's side: the request out and the response in. */
    private def dbusPorts: Map[String, Int] = Map(
        "dbus_req_ready" -> 1,
        "dbus_req_valid" -> 1,
        "dbus_req_bits_addr" -> width,
        "dbus_req_bits_wdata" -> width,
        "dbus_req_bits_wen" -> 1,
        "dbus_req_bits_wstrb" -> 4,
        "dbus_req_bits_user" -> DbusUser.userWidth,
        "dbus_req_bits_id" -> 1,
        "dbus_resp_ready" -> 1,
        "dbus_resp_valid" -> 1,
        "dbus_resp_bits_data" -> width,
        "dbus_resp_bits_user" -> DbusUser.userWidth,
        "dbus_resp_bits_id" -> 1
    )

    // ── The paths ──
    /** The standalone memory path is lowered with a window of its own: the back's elaboration instantiates the memory
      * path with the back's window, and a second lowering of the same parameter writes no module — a generator
      * elaborates a given parameter once per process.
      */
    private val memWindow = "0x20000000:0x1000"
    private val memModule = MemPath.moduleName(MemPathParameter(Isa("riscv32i"), 0x80000000L, Seq(memWindow)))

    /** Lowerings are memoized per configuration: a generator object elaborates a given parameter once per process
      * (`elaboratedModules`), so a second lowering of the same parameters writes no module and the link would fail.
      */
    private val lowerings = scala.collection.mutable.Map[String, String]()

    private def intModule(isa: String): String =
        IntPath.moduleName(
            IntPathParameter(
                Isa(isa),
                0x80000000L,
                RtlFlow.Yosys,
                StatScope(Seq("int")),
                Some(BpuConfig(BpuStrategy.Bimodal(64, 4), None))
            )
        )

    private def intSv(isa: String): String =
        lowerings.getOrElseUpdate(
            s"int:$isa",
            TestFlow.lower(
                intModule(isa),
                cfg =>
                    IntPath.main(
                        Array(
                            "config",
                            cfg.toString,
                            "--isa",
                            isa,
                            "--defaultPc",
                            "2147483648",
                            "--flow",
                            "yosys",
                            "--bpu",
                            "bimodal:64:4",
                            "--stats",
                            "int"
                        )
                    ),
                cfg => IntPath.main(Array("design", cfg.toString))
            )
        )

    private lazy val memSv = TestFlow.lower(
        memModule,
        cfg =>
            MemPath.main(
                Array(
                    "config",
                    cfg.toString,
                    "--isa",
                    "riscv32i",
                    "--defaultPc",
                    "2147483648",
                    "--mmioRanges",
                    memWindow
                )
            ),
        cfg => MemPath.main(Array("design", cfg.toString))
    )

    /** The issued uop's fields, under whichever prefix the port carries them. */
    private def issuePorts(prefix: String): Map[String, Int] = Map(
        s"${prefix}_valid" -> 1,
        s"${prefix}_first" -> 1,
        s"${prefix}_bits_pc" -> width,
        s"${prefix}_bits_next_pc" -> width,
        s"${prefix}_bits_fall_through" -> width,
        s"${prefix}_bits_pht_count" -> BpUpdate.phtCountBits,
        s"${prefix}_bits_src_0" -> width,
        s"${prefix}_bits_src_1" -> width,
        s"${prefix}_bits_src_2" -> width,
        s"${prefix}_bits_src_3" -> width,
        s"${prefix}_bits_backend" -> Backend.width,
        s"${prefix}_bits_op" -> OpWindow.width(config),
        s"${prefix}_bits_csr_type" -> CsrType.width,
        s"${prefix}_bits_rd_index" -> Gpr.indexWidth
    )

    /** The completion report's fields, under whichever prefix the port carries them. */
    private def resultPorts(prefix: String): Map[String, Int] = Map(
        s"${prefix}_data" -> width,
        s"${prefix}_next_pc" -> width,
        s"${prefix}_csr_type" -> CsrType.width,
        s"${prefix}_csr_data" -> width,
        s"${prefix}_is_mmio" -> 1,
        s"${prefix}_mispredict" -> 1
    )

    /** The sysu offer's fields, under whichever prefix the port carries them. */
    private def sysuOfferPorts(prefix: String): Map[String, Int] = Map(
        s"${prefix}_valid" -> 1,
        s"${prefix}_bits_csr_type" -> CsrType.width,
        s"${prefix}_bits_data" -> width
    )

    // ── The completion path ──
    private val completionModule =
        Completion.moduleName(CompletionParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Yosys, 0, StatScope.root))

    private def completionSv(depth: Int): String =
        TestFlow.lower(
            completionModule,
            cfg =>
                Completion.main(
                    Array(
                        "config",
                        cfg.toString,
                        "--isa",
                        "riscv32i",
                        "--defaultPc",
                        "2147483648",
                        "--flow",
                        "yosys",
                        "--stats",
                        "root"
                    ) ++ Seq("--lsqDepth", depth.toString)
                ),
            cfg => Completion.main(Array("design", cfg.toString))
        )

    private lazy val queued = completionSv(4)
    private lazy val plain = completionSv(0)

    // ── The forward network ──
    private def forwardSv(level: BypassLevel, depth: Int): String =
        lowerings.getOrElseUpdate(
            s"${level.name}:$depth",
            TestFlow.lower(
                Forward.moduleName(ForwardParameter(Isa("riscv32i"), 0x80000000L, level, depth)),
                cfg =>
                    Forward.main(
                        Array(
                            "config",
                            cfg.toString,
                            "--isa",
                            "riscv32i",
                            "--defaultPc",
                            "2147483648",
                            "--bypass",
                            level.name,
                            "--lsqDepth",
                            depth.toString
                        )
                    ),
                cfg => Forward.main(Array("design", cfg.toString))
            )
        )

    val tests = Tests:
        // ── The back ──
        test("the back's boundary is the middle's face and the core's two ports"):
            assert(sv.contains(s"module $backModule("))
            assert(
                ports == Map(
                    "clock" -> 1,
                    "resetN" -> 1,
                    "flush_valid" -> 1,
                    "flush_next_pc" -> width,
                    "forward_commit_armed" -> 1
                ) ++ movedPorts ++ retirePorts("retire") ++ dbusPorts
                    ++ (0 to depth).flatMap(offerPorts).toMap
                    ++ (0 to depth).flatMap(csrOfferPorts).toMap
            )

        // The training sideband is the predictor's: with no predictor there is no one to train, so the port — and the
        // registers behind it in the int path — do not exist. The front's own test pins the other configuration.
        test("the predictor's training follows the config"):
            assert(!ports.contains("bp_update_valid"))
            assert(!sv.contains("updateValid"))

        // The whole point of the module: the queue's face with the memory path and the two paths' faces are wired
        // inside, so nothing of them reaches the middle.
        test("the execution face stays inside the back"):
            val internal =
                Seq("queue_canAccept", "queue_flushRisk", "queue_reqTag", "int_issue_valid", "mem_issue_valid")
            for port <- internal do assert(!ports.contains(port))

        // The execute slot is this module's — the register is named here — while the CSR file, architectural state, is
        // the middle's: the back only publishes the in-flight CSR writers, and the middle's read resolves them.
        test("the back registers the execute slot, and not the CSR file"):
            assert(sv.contains("isexValid"))
            assert(!sv.contains("module CsrFile"))

        test("the back instantiates the dispatch, the completion path with its queue, the network and the two paths"):
            for unit <- Seq("Dispatch", "Completion", "Forward", "IntPath", "MemPath", "CompletionQueue") do
                assert(sv.contains(s"module $unit("))

        // ── The paths ──
        test("the int path's boundary is the dispatch's face and the resolved branches"):
            assert(
                TestFlow.ports(intSv("riscv32i"), intModule("riscv32i")) ==
                    Map(
                        "clock" -> 1,
                        "resetN" -> 1,
                        "path_done" -> 1,
                        "bp_update_valid" -> 1,
                        "bp_update_bits_pc" -> width,
                        "bp_update_bits_taken" -> 1,
                        "bp_update_bits_target" -> width,
                        "bp_update_bits_pht_count" -> BpUpdate.phtCountBits
                    ) ++ issuePorts("path_issue") ++ resultPorts("path_result") ++ sysuOfferPorts("path_csr_offer")
            )

        test("the memory path's boundary is the dispatch's face and the data bus"):
            assert(
                TestFlow.ports(memSv, memModule) ==
                    Map(
                        "clock" -> 1,
                        "resetN" -> 1,
                        "path_done" -> 1,
                        "path_slotRelease" -> 1,
                        "queue_reqTag" -> DbusUser.tagWidth,
                        "queue_offset" -> LsuData.offsetWidth(width),
                        "queue_resp_valid" -> 1,
                        "queue_resp_bits_tag" -> DbusUser.tagWidth,
                        "queue_resp_bits_data" -> width,
                        "queue_canAccept" -> 1,
                        "queue_flushRisk" -> 1,
                        "dbus_req_valid" -> 1,
                        "dbus_req_ready" -> 1,
                        "dbus_req_bits_addr" -> width,
                        "dbus_req_bits_wdata" -> width,
                        "dbus_req_bits_wen" -> 1,
                        "dbus_req_bits_wstrb" -> (width / 8),
                        "dbus_req_bits_user" -> DbusUser.userWidth,
                        "dbus_req_bits_id" -> MemPath.busIdWidth,
                        "dbus_resp_valid" -> 1,
                        "dbus_resp_ready" -> 1,
                        "dbus_resp_bits_data" -> width,
                        "dbus_resp_bits_user" -> DbusUser.userWidth,
                        "dbus_resp_bits_id" -> MemPath.busIdWidth
                    ) ++ issuePorts("path_issue") ++ resultPorts("path_result")
            )

        test("the int path instantiates the M units only with the M extension"):
            for unit <- Seq("Mul", "Div") do
                assert(!intSv("riscv32i").contains(s"module $unit("))
                assert(intSv("riscv32im").contains(s"module $unit("))

        test("mmio windows parse as hex base:size pairs"):
            assert(MmioWindow.parse("0x10000000:0x1000") == (BigInt("10000000", 16), BigInt("1000", 16)))
            assert(MmioWindow.parse("8000_0000:100") == (BigInt("80000000", 16), BigInt("100", 16)))
            intercept[IllegalArgumentException](MmioWindow.parse("0x1000"))

        // ── The completion path ──
        test("the completion path is the queue when the configuration builds one"):
            assert(queued.contains("module CompletionQueue("))
            assert(!plain.contains("module CompletionQueue("))

        test("the retire stream and the slot pace have the same shape either way"):
            for lowered <- Seq(queued, plain) do
                val completionPorts = TestFlow.ports(lowered, completionModule)
                assert(completionPorts("retire_valid") == 1)
                assert(completionPorts("retire_bits_mispredicted") == 1)
                assert(completionPorts("pace") == 1)
                assert(completionPorts("queue_canAccept") == 1)
                assert(completionPorts("queue_flushRisk") == 1)

        test("the entries view is the queue's, or a constant when none is built"):
            assert(queued.contains("view_entries_3_valid"))
            assert(!plain.contains("view_entries_1_valid"))

        // ── The forward network ──
        // The level is the column's `armed` bit: a writer the level cannot forward is broadcast unarmed, which is what
        // keeps a read that names it waiting. The column's shape and the writers' identity do not move with the level —
        // the same offers carry the same registers and values in every configuration.
        test("the level is the column's armed bit"):
            val commit = forwardSv(BypassLevel.Commit, 0)
            val executed = forwardSv(BypassLevel.Execute, 0)
            val none = forwardSv(BypassLevel.None, 0)
            // The retiring write's verdict crosses as a bit — the write itself is the middle's register, matched there.
            assert(commit.contains("assign forward_commit_armed = 1'h1"))
            assert(!none.contains("assign forward_commit_armed = 1'h1"))
            // The slot's writer is armed only where the level reaches the completing value — below it, the read waits
            // the instruction's whole residence.
            assert(executed.contains("& exec_valid"))
            assert(!commit.contains("& exec_valid"))

        // The column is the whole wait rule and the queue's unfinished entries are part of it: an entry's armed bit is
        // its own `done`, so the load still on the bus is offered unarmed — which is what keeps a read behind it
        // waiting.
        test("the queue's unfinished entries ride the column"):
            val queuedColumn = forwardSv(BypassLevel.Execute, 4)
            assert(queuedColumn.contains("& view_entries_0_done"))
            assert(queuedColumn.contains("assign forward_arf_offers_4_armed"))
