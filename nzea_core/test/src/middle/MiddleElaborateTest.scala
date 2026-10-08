package nzea_core.middle

import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.back.{BpUpdate, OpWindow}
import nzea_core.back.mem.{DbusUser, LsuData}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa, Srcs}
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the middle's boundary is the decoded stream it consumes, the dispatch and the merge it holds with
  * the two execution paths, and the two things it hands the top (the flush event, the commit trace). Inside, it holds
  * the two files both stages face and the completion path whose wires are its own.
  */
object MiddleElaborateTest extends TestSuite:

    private val width = 32
    private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)

    private val module = Middle.moduleName(
        MiddleParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Yosys, BypassLevel.Commit, 0, StatScope.root)
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
                        "--bypass",
                        "commit",
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

    /** One gated issue stream's fields, under whichever prefix the port carries them. */
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

    /** One path's completion report, under its own prefix. */
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
        test("the middle's boundary is the decoded stream, the dispatch, the merge, the commit and the flush"):
            assert(sv.contains(s"module $module("))
            assert(
                ports == Map(
                    "clock" -> 1,
                    "resetN" -> 1,
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
                    "int_done" -> 1,
                    "mem_done" -> 1,
                    "mem_slotRelease" -> 1,
                    "queue_reqTag" -> DbusUser.tagWidth,
                    "queue_offset" -> LsuData.offsetWidth(width),
                    "queue_resp_valid" -> 1,
                    "queue_resp_bits_tag" -> DbusUser.tagWidth,
                    "queue_resp_bits_data" -> width,
                    "queue_canAccept" -> 1,
                    "queue_flushRisk" -> 1,
                    "commit_valid" -> 1,
                    "commit_bits_next_pc" -> width,
                    "commit_bits_rd_index" -> Gpr.indexWidth,
                    "commit_bits_rd_value" -> width,
                    "commit_bits_is_mmio" -> 1,
                    "commit_bits_csr_valid" -> 1,
                    "commit_bits_csr_addr" -> CsrType.addrWidth,
                    "commit_bits_csr_data" -> width,
                    "flush_valid" -> 1,
                    "flush_next_pc" -> width
                ) ++ issuePorts("int_issue") ++ issuePorts("mem_issue") ++ resultPorts(
                    "int_result"
                ) ++ resultPorts("mem_result")
            )

        test("the middle instantiates its three stages, the two files they share and the stages' units"):
            assert(sv.contains("module Isu"))
            assert(sv.contains("module Exu"))
            assert(sv.contains("module Wbu"))
            assert(sv.contains("module Arf"))
            assert(sv.contains("module CsrFile"))
            assert(sv.contains("module OperandRead"))
            assert(sv.contains("module Assemble"))
            assert(sv.contains("module Dispatch"))
            assert(sv.contains("module Completion"))
            assert(sv.contains("module Forward"))

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

        // The forward network's column is the read's to match: the middle carries the bus from the network to the read,
        // and the read takes the value of the first armed offer that names its register.
        test("the forward bus reaches the read unit"):
            assert(sv.contains("offers_0_armed") && sv.contains("offers_1_armed"))

        // The stage owns no register either: its two boundaries are the middle's, declared one level up.
        test("the issue stage is purely combinational"):
            val start = sv.indexOf("module Isu(")
            assert(start >= 0)
            val body = sv.substring(start, sv.indexOf("endmodule", start))
            assert(!body.contains("always @(posedge"))

        // The assembly owns no state: the `Assemble` module in the same lowering has no flop and no stage register.
        test("the assembly unit is purely combinational"):
            val start = sv.indexOf("module Assemble(")
            assert(start >= 0)
            val body = sv.substring(start, sv.indexOf("endmodule", start))
            assert(!body.contains("always @(posedge"))
            assert(!body.contains("idisValid") && !body.contains("isexValid"))
