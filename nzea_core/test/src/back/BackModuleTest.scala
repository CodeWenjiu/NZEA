package nzea_core.back

import nzea_core.back.mem.DbusUser
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the back's boundary is the middle's face in and the core's two ports out — the execute slot's
  * register, the queue's face with the memory path, the dispatch and the merge are its own wiring and never cross it.
  */
object BackModuleTest extends TestSuite:

    private val width = 32
    private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)
    private val depth = 4
    private val window = "0x10000000:0x1000"

    private val module =
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
            module,
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

    private lazy val ports = TestFlow.ports(sv, module)

    /** The read's uop, in: the elastic handshake and the IS/EX register's payload — the register is this module's. */
    private def movedPorts: Map[String, Int] = Map(
        "moved_valid" -> 1,
        "moved_ready" -> 1,
        "moved_bits_pc" -> width,
        "moved_bits_next_pc" -> width,
        "moved_bits_fall_through" -> width,
        "moved_bits_pht_count" -> BpUpdate.phtCountBits,
        "moved_bits_imm" -> width,
        "moved_bits_src_a" -> width,
        "moved_bits_src_b" -> width,
        "moved_bits_src_c" -> width,
        "moved_bits_backend" -> Backend.width,
        "moved_bits_op" -> OpWindow.width(config),
        "moved_bits_rd_index" -> Gpr.indexWidth,
        "moved_bits_csr_type" -> CsrType.width,
        "moved_bits_csr_rdata" -> width
    )

    /** One retiring record, under its own prefix. */
    private def retirePorts(prefix: String): Map[String, Int] = Map(
        s"${prefix}_valid" -> 1,
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

    val tests = Tests:
        test("the back's boundary is the middle's face and the core's two ports"):
            assert(sv.contains(s"module $module("))
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
            for unit <- Seq("Dispatch", "Completion", "Forward", "IntPath", "MemPath", "Lsq") do
                assert(sv.contains(s"module $unit("))
