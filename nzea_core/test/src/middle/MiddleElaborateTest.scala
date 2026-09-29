package nzea_core.middle

import nzea_core.{BypassConfig, CoreConfig}
import nzea_core.back.OpWindow
import nzea_core.isa.{Backend, CsrType, Gpr, Isa, Srcs}
import nzea_rtl.{RtlFlow, StatScope, TestFlow}
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the middle's boundary is the decoded stream it consumes, the three things it hands the front and
  * the top (flush, redirect, commit), the uop it issues, and the report it retires.
  */
object MiddleElaborateTest extends TestSuite:

  private val width = 32
  private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)

  private val module = Middle.moduleName(
    MiddleParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Yosys, BypassConfig.writeback, StatScope.root)
  )

  private def lower(bypass: BypassConfig): String =
    TestFlow.lower(
      module,
      cfg =>
        Middle.main(
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
            bypass.name,
            "--stats",
            "root"
          )
        ),
      cfg => Middle.main(Array("design", cfg.toString))
    )

  private lazy val sv = lower(BypassConfig.writeback)

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
          "decoded_bits_rs1_index" -> Gpr.indexWidth,
          "decoded_bits_rs2_index" -> Gpr.indexWidth,
          "decoded_bits_srcs" -> Srcs.width,
          "decoded_bits_backend" -> Backend.width,
          "decoded_bits_op" -> OpWindow.width(config),
          "decoded_bits_csr_addr" -> 12,
          "redirect_valid" -> 1,
          "redirect_bits" -> width,
          "issue_valid" -> 1,
          "issue_first" -> 1,
          "issue_bits_pc" -> width,
          "issue_bits_imm" -> width,
          "issue_bits_src_a" -> width,
          "issue_bits_src_b" -> width,
          "issue_bits_src_c" -> width,
          "issue_bits_backend" -> Backend.width,
          "issue_bits_op" -> OpWindow.width(config),
          "issue_bits_csr_type" -> CsrType.width,
          "issue_bits_csr_rdata" -> width,
          "report_complete" -> 1,
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

    test("the middle instantiates the register file and the CSR file"):
      assert(sv.contains("module Arf"))
      assert(sv.contains("module CsrFile"))

    // The read is the middle's, and the one producer it cannot see yet is the instruction in execute: a payload naming
    // that register waits for the completion to pass, so the comparison runs against the execute slot's own destination
    // — `exRd` in the lowered RTL — rather than against the retiring instruction's.
    test("a payload that reads the instruction in execute waits for it"):
      assert(sv.contains("exRd"))
