package nzea_core.middle

import nzea_config.RtlFlow
import nzea_core.back.OpWindow
import nzea_core.isa.{Backend, CsrType, Gpr}
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the middle's boundary is the decoded stream it consumes, the three things it hands the front and
  * the top (flush, redirect, write-back), the uop it issues, and the report it retires.
  */
object MiddleElaborateTest extends TestSuite:

  private val width = 32
  private val module = Middle.moduleName(MiddleParameter("riscv32i", 0x80000000L, RtlFlow.Yosys))

  private lazy val sv = TestFlow.lower(
    module,
    cfg =>
      Middle.main(
        Array("config", cfg.toString, "--isa", "riscv32i", "--defaultPc", "2147483648", "--flow", "yosys")
      ),
    cfg => Middle.main(Array("design", cfg.toString))
  )

  private lazy val ports = TestFlow.ports(sv, module)

  val tests = Tests:
    test("the middle's boundary is the decoded stream, the flush and the retire channels"):
      assert(sv.contains(s"module $module("))
      assert(
        ports == Map(
          "clock" -> 1,
          "resetN" -> 1,
          "flush" -> 1,
          "decoded_valid" -> 1,
          "decoded_ready" -> 1,
          "decoded_bits_pc" -> width,
          "decoded_bits_imm" -> width,
          "decoded_bits_rd_index" -> Gpr.indexWidth,
          "decoded_bits_src_a" -> width,
          "decoded_bits_src_b" -> width,
          "decoded_bits_src_c" -> width,
          "decoded_bits_backend" -> Backend.width,
          "decoded_bits_op" -> OpWindow.Width,
          "decoded_bits_csr_addr" -> 12,
          "redirect_valid" -> 1,
          "redirect_bits" -> width,
          "arf_write_valid" -> 1,
          "arf_write_bits_rd_index" -> Gpr.indexWidth,
          "arf_write_bits_data" -> width,
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
          "commit_valid" -> 1,
          "commit_bits_next_pc" -> width,
          "commit_bits_rd_index" -> Gpr.indexWidth,
          "commit_bits_rd_value" -> width,
          "commit_bits_is_mmio" -> 1,
          "commit_bits_csr_type" -> CsrType.width,
          "commit_bits_csr_data" -> width
        )
      )

    test("the middle instantiates the CSR file"):
      assert(sv.contains("module CsrFile"))
