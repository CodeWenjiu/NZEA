package nzea_core.back

import nzea_core.config.{BpuConfig, BpuStrategy, BypassLevel, CoreConfig}
import nzea_core.back.mem.{DbusUser, MemPath, MmioWindow}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the backend's boundary (the issued uop in, the completion and the data bus out) and the paths it
  * instantiates — including that the M units only exist for an ISA that has M.
  */
object BackPathTest extends TestSuite:

    private val width = 32
    private val window = "0x10000000:0x1000"

    private val module =
        Back.moduleName(
            BackParameter(
                Isa("riscv32i"),
                0x80000000L,
                RtlFlow.Yosys,
                Seq(window),
                BypassLevel.Commit,
                Some(BpuConfig(BpuStrategy.Bimodal(128, 8), None)),
                0,
                StatScope.root
            )
        )

    /** Lowerings are memoized per configuration: a generator object elaborates a given parameter once per process
      * (`elaboratedModules`), so a second lowering of the same parameters writes no module and the link would fail.
      */
    private val lowerings = scala.collection.mutable.Map[(String, BypassLevel, Int), String]()

    private def elaborate(isa: String, level: BypassLevel = BypassLevel.Commit, depth: Int = 0): String =
        lowerings.getOrElseUpdate(
            (isa, level, depth),
            TestFlow.lower(
                module,
                cfg =>
                    Back.main(
                        Array(
                            "config",
                            cfg.toString,
                            "--isa",
                            isa,
                            "--defaultPc",
                            "2147483648",
                            "--flow",
                            "yosys",
                            "--mmioRanges",
                            window,
                            "--bypass",
                            level.name,
                            "--lsqDepth",
                            depth.toString,
                            "--bpu",
                            "bimodal:128:8",
                            "--stats",
                            "root"
                        )
                    ),
                cfg => Back.main(Array("design", cfg.toString))
            )
        )

    private lazy val rv32i = elaborate("riscv32i")
    private lazy val rv32im = elaborate("riscv32im")

    val tests = Tests:
        test("the backend's boundary is the issued uop, the completion report and the data bus"):
            assert(
                TestFlow.ports(rv32i, module) == Map(
                    "clock" -> 1,
                    "resetN" -> 1,
                    "issue_valid" -> 1,
                    "issue_first" -> 1,
                    "issue_bits_pc" -> width,
                    "issue_bits_next_pc" -> width,
                    "issue_bits_fall_through" -> width,
                    "issue_bits_imm" -> width,
                    "issue_bits_src_a" -> width,
                    "issue_bits_src_b" -> width,
                    "issue_bits_src_c" -> width,
                    "issue_bits_backend" -> Backend.width,
                    "issue_bits_op" -> OpWindow.width(CoreConfig(Isa("riscv32i"), 0x80000000L)),
                    "issue_bits_csr_type" -> CsrType.width,
                    "issue_bits_csr_rdata" -> width,
                    "issue_bits_rd_index" -> Gpr.indexWidth,
                    "issue_bits_pht_count" -> BpUpdate.phtCountBits,
                    "bp_update_valid" -> 1,
                    "bp_update_bits_pc" -> width,
                    "bp_update_bits_taken" -> 1,
                    "bp_update_bits_target" -> width,
                    "bp_update_bits_pht_count" -> BpUpdate.phtCountBits,
                    "retire_valid" -> 1,
                    "retire_bits_next_pc" -> width,
                    "retire_bits_rd_index" -> Gpr.indexWidth,
                    "retire_bits_data" -> width,
                    "retire_bits_csr_type" -> CsrType.width,
                    "retire_bits_csr_data" -> width,
                    "retire_bits_is_mmio" -> 1,
                    "retire_bits_mispredicted" -> 1,
                    "slotRelease" -> 1,
                    "flush_valid" -> 1,
                    "flush_next_pc" -> width,
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
                    "dbus_resp_bits_id" -> MemPath.busIdWidth,
                    "forward_commit_armed" -> 1,
                    "forward_offers_0_valid" -> 1,
                    "forward_offers_0_armed" -> 1,
                    "forward_offers_0_index" -> Gpr.indexWidth,
                    "forward_offers_0_data" -> width,
                    "forward_offers_1_valid" -> 1,
                    "forward_offers_1_armed" -> 1,
                    "forward_offers_1_index" -> Gpr.indexWidth,
                    "forward_offers_1_data" -> width
                )
            )

        test("the backend instantiates both execution paths, and the M units only with the M extension"):
            for path <- Seq("IntPath", "MemPath") do assert(rv32i.contains(s"module $path("))
            for unit <- Seq("Mul", "Div") do
                assert(!rv32i.contains(s"module $unit("))
                assert(rv32im.contains(s"module $unit("))

        // The level is the column's `armed` bit: a writer the level cannot forward is broadcast unarmed, which is what
        // keeps a read that names it waiting. The column's shape and the writers' identity do not move with the level —
        // the same offers carry the same registers and values in every configuration.
        test("the level is the column's armed bit"):
            val executed = elaborate("riscv32i", BypassLevel.Execute)
            val none = elaborate("riscv32i", BypassLevel.None)
            assert(rv32i.contains("module Forward("))
            // The retiring write's verdict crosses as a bit — the write itself is the middle's register, matched there.
            assert(rv32i.contains("assign commit_armed = 1'h1"))
            assert(!none.contains("assign commit_armed = 1'h1"))
            // The slot's writer is armed only where the level reaches the completing value — below it, the read waits
            // the instruction's whole residence.
            assert(executed.contains("& exec_valid"))
            assert(!rv32i.contains("& exec_valid"))

        // The column is the whole wait rule and the queue's unfinished entries are part of it: an entry that names a
        // register without being armed keeps a read of it waiting — the load still on the bus is what makes a read
        // behind it hold.
        test("the queue's unfinished entries ride the column"):
            val queued = elaborate("riscv32i", BypassLevel.Execute, 4)
            assert(queued.contains("~entries_0_done"))
            assert(queued.contains("assign offers_4_armed"))

        test("mmio windows parse as hex base:size pairs"):
            assert(MmioWindow.parse("0x10000000:0x1000") == (BigInt("10000000", 16), BigInt("1000", 16)))
            assert(MmioWindow.parse("8000_0000:100") == (BigInt("80000000", 16), BigInt("100", 16)))
            intercept[IllegalArgumentException](MmioWindow.parse("0x1000"))
