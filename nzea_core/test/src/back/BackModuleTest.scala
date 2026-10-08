package nzea_core.back

import nzea_core.back.mem.DbusUser
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the back's boundary is the middle's face in and the core's two ports out — the queue's face with
  * the memory path, the dispatch and the merge are its own wiring and never cross it.
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

    /** The backend's uop, under the port's own prefix. */
    private def issuePorts(prefix: String): Map[String, Int] = Map(
        s"${prefix}_valid" -> 1,
        s"${prefix}_first" -> 1,
        s"${prefix}_bits_pc" -> width,
        s"${prefix}_bits_next_pc" -> width,
        s"${prefix}_bits_fall_through" -> width,
        s"${prefix}_bits_pht_count" -> BpUpdate.phtCountBits,
        s"${prefix}_bits_imm" -> width,
        s"${prefix}_bits_src_a" -> width,
        s"${prefix}_bits_src_b" -> width,
        s"${prefix}_bits_src_c" -> width,
        s"${prefix}_bits_backend" -> Backend.width,
        s"${prefix}_bits_op" -> OpWindow.width(config),
        s"${prefix}_bits_csr_type" -> CsrType.width,
        s"${prefix}_bits_csr_rdata" -> width,
        s"${prefix}_bits_rd_index" -> Gpr.indexWidth
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

    /** One offer of the forward column: the slot's writer, then the queue's entries. */
    private def offerPorts(index: Int): Map[String, Int] = Map(
        s"offers_${index}_valid" -> 1,
        s"offers_${index}_armed" -> 1,
        s"offers_${index}_index" -> Gpr.indexWidth,
        s"offers_${index}_data" -> width
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
        test("the back's boundary is the middle's face, the data bus and the predictor's training"):
            assert(sv.contains(s"module $module("))
            assert(
                ports == Map(
                    "clock" -> 1,
                    "resetN" -> 1,
                    "flush_valid" -> 1,
                    "flush_next_pc" -> width,
                    "pace" -> 1,
                    "commit_armed" -> 1,
                    "bp_update_valid" -> 1,
                    "bp_update_bits_pc" -> width,
                    "bp_update_bits_taken" -> 1,
                    "bp_update_bits_target" -> width,
                    "bp_update_bits_pht_count" -> BpUpdate.phtCountBits
                ) ++ issuePorts("issue") ++ retirePorts("retire") ++ dbusPorts
                    ++ (0 to depth).flatMap(offerPorts).toMap
            )

        // The whole point of the module: the queue's face with the memory path and the two paths' faces are wired
        // inside, so nothing of them reaches the middle.
        test("the execution face stays inside the back"):
            val internal =
                Seq("queue_canAccept", "queue_flushRisk", "queue_reqTag", "int_issue_valid", "mem_issue_valid")
            for port <- internal do assert(!ports.contains(port))

        test("the back instantiates the dispatch, the completion path, the network and the two paths"):
            for unit <- Seq("Dispatch", "Completion", "Forward", "IntPath", "MemPath", "Lsq") do
                assert(sv.contains(s"module $unit("))
