package nzea_core.back

import nzea_core.config.CoreConfig
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.back.mem.{DbusUser, LsuData, MemPath, MemPathParameter, MmioWindow}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: each execution path's boundary — the middle's face in, the data bus for the memory path — and the
  * units the M extension adds. The paths are pure units: the dispatch and the merge that used to wrap them live in the
  * middle's execution face.
  */
object BackPathTest extends TestSuite:

    private val width = 32
    private val window = "0x10000000:0x1000"
    private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)

    private val memModule = MemPath.moduleName(MemPathParameter(Isa("riscv32i"), 0x80000000L, Seq(window)))

    /** Lowerings are memoized per configuration: a generator object elaborates a given parameter once per process
      * (`elaboratedModules`), so a second lowering of the same parameters writes no module and the link would fail.
      */
    private val lowerings = scala.collection.mutable.Map[String, String]()

    private def intModule(isa: String): String =
        IntPath.moduleName(IntPathParameter(Isa(isa), 0x80000000L, RtlFlow.Yosys, StatScope(Seq("int")), None))

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
                    window
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

    /** The completion report's fields, under whichever prefix the port carries them. */
    private def resultPorts(prefix: String): Map[String, Int] = Map(
        s"${prefix}_data" -> width,
        s"${prefix}_next_pc" -> width,
        s"${prefix}_csr_type" -> CsrType.width,
        s"${prefix}_csr_data" -> width,
        s"${prefix}_is_mmio" -> 1,
        s"${prefix}_is_branch" -> 1,
        s"${prefix}_pred_next_pc" -> width
    )

    val tests = Tests:
        test("the int path's boundary is the middle's face and the resolved branches"):
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
                    ) ++ issuePorts("path_issue") ++ resultPorts("path_result")
            )

        test("the memory path's boundary is the middle's face and the data bus"):
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
