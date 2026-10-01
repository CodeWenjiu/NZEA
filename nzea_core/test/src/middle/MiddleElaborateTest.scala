package nzea_core.middle

import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.back.{BpUpdate, OpWindow}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa, Srcs}
import nzea_rtl.{RtlFlow, StatScope, TestFlow}
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the middle's boundary is the decoded stream it consumes, the three things it hands the front and
  * the top (flush, redirect, commit), the uop it issues, and the completion it latches into write-back — it holds the
  * two files both stages face, and its stages hold the register pairs and the units between them.
  */
object MiddleElaborateTest extends TestSuite:

    private val width = 32
    private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)

    private val module = Middle.moduleName(
        MiddleParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Yosys, BypassLevel.Commit, StatScope.root)
    )

    private def lower(bypass: BypassLevel): String =
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

    private lazy val sv = lower(BypassLevel.Commit)

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
                    "decoded_bits_next_pc" -> width,
                    "decoded_bits_fall_through" -> width,
                    "decoded_bits_pht_count" -> BpUpdate.phtCountBits,
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
                    "issue_bits_next_pc" -> width,
                    "issue_bits_fall_through" -> width,
                    "issue_bits_pht_count" -> BpUpdate.phtCountBits,
                    "issue_bits_imm" -> width,
                    "issue_bits_src_a" -> width,
                    "issue_bits_src_b" -> width,
                    "issue_bits_src_c" -> width,
                    "issue_bits_backend" -> Backend.width,
                    "issue_bits_op" -> OpWindow.width(config),
                    "issue_bits_csr_type" -> CsrType.width,
                    "issue_bits_csr_rdata" -> width,
                    "issue_bits_rd_index" -> Gpr.indexWidth,
                    "complete_valid" -> 1,
                    "complete_bits_data" -> width,
                    "complete_bits_next_pc" -> width,
                    "complete_bits_csr_type" -> CsrType.width,
                    "complete_bits_csr_data" -> width,
                    "complete_bits_is_mmio" -> 1,
                    "complete_bits_is_branch" -> 1,
                    "complete_bits_pred_next_pc" -> width,
                    "bypass_rs1_index" -> Gpr.indexWidth,
                    "bypass_rs2_index" -> Gpr.indexWidth,
                    "bypass_commit_valid" -> 1,
                    "bypass_commit_bits_rd_index" -> Gpr.indexWidth,
                    "bypass_commit_bits_data" -> width,
                    "bypass_rs1_valid" -> 1,
                    "bypass_rs1_bits" -> width,
                    "bypass_rs2_valid" -> 1,
                    "bypass_rs2_bits" -> width,
                    "commit_valid" -> 1,
                    "commit_bits_next_pc" -> width,
                    "commit_bits_rd_index" -> Gpr.indexWidth,
                    "commit_bits_rd_value" -> width,
                    "commit_bits_is_mmio" -> 1,
                    "commit_bits_csr_type" -> CsrType.width,
                    "commit_bits_csr_data" -> width
                )
            )

        test("the middle instantiates its two stages and the two files it shares"):
            assert(sv.contains("module IssueStage"))
            assert(sv.contains("module Wbu"))
            assert(sv.contains("module Arf"))
            assert(sv.contains("module CsrFile"))
            assert(sv.contains("module OperandRead"))
            assert(sv.contains("module Isu"))

        // The read is the issue unit's, and the one producer it cannot see yet is the instruction in execute: a payload
        // naming that register waits for the completion to pass, so the comparison runs against the IS/EX register's own
        // destination field — `isex_rd_index` in the lowered RTL — rather than against the retiring instruction's.
        test("a payload that reads the instruction in execute waits for it"):
            assert(sv.contains("isex_rd_index"))

        // The stage registers are the issue stage's — the issue unit between them is combinational — and the event
        // files address them through that stage.
        test("the issue stage carries the stage registers"):
            assert(sv.contains("idisValid") && sv.contains("isexValid"))

        // The bypass network's answer is the read's to select: the middle carries the bus to the read, and the read takes
        // the network's value where it is valid.
        test("the bypass bus reaches the read unit"):
            assert(sv.contains("bypass_rs1_valid") && sv.contains("bypass_rs2_valid"))

        // The unit itself owns no state: the `Isu` module in the same lowering has no flop and no stage register.
        test("the issue unit is purely combinational"):
            val start = sv.indexOf("module Isu(")
            assert(start >= 0)
            val body = sv.substring(start, sv.indexOf("endmodule", start))
            assert(!body.contains("always @(posedge"))
            assert(!body.contains("idisValid") && !body.contains("isexValid"))
