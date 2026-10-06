package nzea_core.middle

import nzea_core.config.CoreConfig
import nzea_core.back.{BpUpdate, OpWindow}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa, Srcs}
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the middle's boundary is the decoded stream it consumes, the two things it hands the top (the
  * flush event, the commit trace), the uop it issues, and the completion it latches into write-back — it holds the two
  * files both stages face, and its stages hold the register pairs and the units between them.
  */
object MiddleElaborateTest extends TestSuite:

    private val width = 32
    private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)

    private val module = Middle.moduleName(
        MiddleParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Yosys, 0, StatScope.root)
    )

    private def lower(): String =
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
                        "--lsqDepth",
                        "0",
                        "--stats",
                        "root"
                    )
                ),
            cfg => Middle.main(Array("design", cfg.toString))
        )

    private lazy val sv = lower()

    private lazy val ports = TestFlow.ports(sv, module)

    val tests = Tests:
        test("the middle's boundary is the decoded stream, the flush and the retire channels"):
            assert(sv.contains(s"module $module("))
            assert(
                ports == Map(
                    "clock" -> 1,
                    "resetN" -> 1,
                    "flush_valid" -> 1,
                    "flush_next_pc" -> width,
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
                    "retire_valid" -> 1,
                    "retire_bits_next_pc" -> width,
                    "retire_bits_rd_index" -> Gpr.indexWidth,
                    "retire_bits_data" -> width,
                    "retire_bits_csr_type" -> CsrType.width,
                    "retire_bits_csr_data" -> width,
                    "retire_bits_is_mmio" -> 1,
                    "retire_bits_mispredicted" -> 1,
                    "slotRelease" -> 1,
                    "forward_commit_armed" -> 1,
                    "forward_offers_0_valid" -> 1,
                    "forward_offers_0_armed" -> 1,
                    "forward_offers_0_index" -> Gpr.indexWidth,
                    "forward_offers_0_data" -> width,
                    "forward_offers_1_valid" -> 1,
                    "forward_offers_1_armed" -> 1,
                    "forward_offers_1_index" -> Gpr.indexWidth,
                    "forward_offers_1_data" -> width,
                    "commit_valid" -> 1,
                    "commit_bits_next_pc" -> width,
                    "commit_bits_rd_index" -> Gpr.indexWidth,
                    "commit_bits_rd_value" -> width,
                    "commit_bits_is_mmio" -> 1,
                    "commit_bits_csr_valid" -> 1,
                    "commit_bits_csr_addr" -> CsrType.addrWidth,
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

        // The read resolves the network's column and its own retiring write: the offer comparisons and the wait are the
        // read's, and the middle knows nothing of the level — an offer that is not armed is all the wait there is.
        test("the read resolves the forward column"):
            val read = TestFlow.ports(sv, "OperandRead")
            assert(read.contains("offers_0_armed") && read.contains("offers_1_armed"))
            assert(read.contains("commit_armed") && read.contains("commit_bits_index"))
            assert(sv.contains("~offers_0_armed"))

        // The stage registers are the issue stage's — the issue unit between them is combinational — and the event
        // files address them through that stage.
        test("the issue stage carries the stage registers"):
            assert(sv.contains("idisValid") && sv.contains("isexValid"))

        // The forward network's column is the read's to match: the middle carries the bus to the read, and the read
        // takes the value of the first armed offer that names its register.
        test("the forward bus reaches the read unit"):
            assert(sv.contains("forward_offers_0_armed") && sv.contains("forward_offers_1_armed"))

        // The unit itself owns no state: the `Isu` module in the same lowering has no flop and no stage register.
        test("the issue unit is purely combinational"):
            val start = sv.indexOf("module Isu(")
            assert(start >= 0)
            val body = sv.substring(start, sv.indexOf("endmodule", start))
            assert(!body.contains("always @(posedge"))
            assert(!body.contains("idisValid") && !body.contains("isexValid"))
