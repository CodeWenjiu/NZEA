package nzea_core.back

import nzea_core.back.mem.{DbusUser, MemPath, MmioWindow}
import nzea_core.isa.{Backend, CsrType}
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the backend's boundary (the issued uop in, the report and the data bus out) and the paths it
  * instantiates — including that the M units only exist for an ISA that has M.
  */
object BackPathTest extends TestSuite:

  private val width = 32
  private val window = "0x10000000:0x1000"
  private val module = Back.moduleName(BackParameter("riscv32i", 0x80000000L, sim = false, mmioRanges = Seq(window)))

  private def elaborate(isa: String): String =
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
            "--sim",
            "false",
            "--mmioRanges",
            window
          )
        ),
      cfg => Back.main(Array("design", cfg.toString))
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
          "issue_bits_pc" -> width,
          "issue_bits_imm" -> width,
          "issue_bits_src_a" -> width,
          "issue_bits_src_b" -> width,
          "issue_bits_src_c" -> width,
          "issue_bits_backend" -> Backend.width,
          "issue_bits_op" -> OpWindow.Width,
          "issue_bits_csr_type" -> CsrType.width,
          "issue_bits_csr_rdata" -> width,
          "report_done" -> 1,
          "report_data" -> width,
          "report_next_pc" -> width,
          "report_csr_type" -> CsrType.width,
          "report_csr_data" -> width,
          "report_is_mmio" -> 1,
          "report_flush" -> 1,
          "report_redirect_target" -> width,
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
        )
      )

    test("the backend instantiates both execution paths, and the M units only with the M extension"):
      for path <- Seq("IntPath", "MemPath") do assert(rv32i.contains(s"module $path("))
      for unit <- Seq("Mul", "Div") do
        assert(!rv32i.contains(s"module $unit("))
        assert(rv32im.contains(s"module $unit("))

    test("mmio windows parse as hex base:size pairs"):
      assert(MmioWindow.parse("0x10000000:0x1000") == (BigInt("10000000", 16), BigInt("1000", 16)))
      assert(MmioWindow.parse("8000_0000:100") == (BigInt("80000000", 16), BigInt("100", 16)))
      intercept[IllegalArgumentException](MmioWindow.parse("0x1000"))
