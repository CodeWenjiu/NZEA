package nzea_core.middle

import nzea_core.config.CoreConfig
import nzea_core.back.{BpUpdate, OpWindow}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa, Srcs}
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the middle's boundary is the decoded stream it consumes, the back's face it holds with the
  * execution subsystem, and the two things it hands the top (the flush event, the commit trace). Inside, it holds the
  * two files both stages face and the two elastic registers whose wires are its own.
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

    /** One offer of the forward column: the slot's writer, then one entry — a dummy without a queue. */
    private def offerPorts(index: Int): Map[String, Int] = Map(
        s"offers_${index}_valid" -> 1,
        s"offers_${index}_armed" -> 1,
        s"offers_${index}_index" -> Gpr.indexWidth,
        s"offers_${index}_data" -> width
    )

    val tests = Tests:
        test("the middle's boundary is the decoded stream, the back's face, the commit and the flush"):
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
                    "pace" -> 1,
                    "commit_armed" -> 1,
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
                ) ++ issuePorts("issue") ++ retirePorts("retire") ++ offerPorts(0) ++ offerPorts(1)
            )

        // The queue's face with the memory path is the back's own wiring: the middle never carries it, and neither does
        // it see a path's face — what crosses is the pipeline's vocabulary, not the execution subsystem's.
        test("the middle carries no execution face: no queue, no path"):
            val internal = Seq("queue_canAccept", "queue_flushRisk", "int_issue_valid", "mem_issue_valid", "int_done")
            for port <- internal do assert(!ports.contains(port))

        test("the middle instantiates its stages, the two files they share and the stages' units"):
            assert(sv.contains("module Isu"))
            assert(sv.contains("module Wbu"))
            assert(sv.contains("module Arf"))
            assert(sv.contains("module CsrFile"))
            assert(sv.contains("module OperandRead"))
            assert(sv.contains("module Assemble"))
            // The execution subsystem is not the middle's: its units are the back's.
            for unit <- Seq("Exu", "Back", "Dispatch", "Completion", "Forward", "IntPath", "MemPath") do
                assert(!sv.contains(s"module $unit("))

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

        // The forward network's column is the read's to match: the middle carries the bus from the back's network to
        // the read, and the read takes the value of the first armed offer that names its register.
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
