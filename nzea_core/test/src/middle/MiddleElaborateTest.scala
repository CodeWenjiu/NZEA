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
  * two files both stages face and the one elastic register whose two ends are its own.
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

    /** The read's uop, out to the back: the elastic handshake and the IS/EX register's payload. */
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

    /** One retiring record, under its own prefix: the elastic handshake and the record's fields. */
    private def retirePorts(prefix: String): Map[String, Int] = Map(
        s"${prefix}_valid" -> 1,
        s"${prefix}_ready" -> 1,
        s"${prefix}_bits_next_pc" -> width,
        s"${prefix}_bits_rd_index" -> Gpr.indexWidth,
        s"${prefix}_bits_data" -> width,
        s"${prefix}_bits_csr_type" -> CsrType.width,
        s"${prefix}_bits_csr_data" -> width,
        s"${prefix}_bits_is_mmio" -> 1,
        s"${prefix}_bits_mispredicted" -> 1
    )

    /** One offer of the register file's column: the slot's writer, then one entry — a dummy without a queue. */
    private def offerPorts(index: Int): Map[String, Int] = Map(
        s"forward_arf_offers_${index}_valid" -> 1,
        s"forward_arf_offers_${index}_armed" -> 1,
        s"forward_arf_offers_${index}_index" -> Gpr.indexWidth,
        s"forward_arf_offers_${index}_data" -> width
    )

    /** One offer of the CSR column: the same writers, indexed by the CSR code — a dummy without a queue. */
    private def csrOfferPorts(index: Int): Map[String, Int] = Map(
        s"forward_csr_offers_${index}_valid" -> 1,
        s"forward_csr_offers_${index}_armed" -> 1,
        s"forward_csr_offers_${index}_index" -> CsrType.width,
        s"forward_csr_offers_${index}_data" -> width
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
                    "forward_commit_armed" -> 1,
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
                ) ++ movedPorts ++ retirePorts("retire") ++ offerPorts(0) ++ offerPorts(1)
                    ++ csrOfferPorts(0) ++ csrOfferPorts(1)
            )

        // The execute stage is not the middle's: its register, its view and its units are the back's, and the queue's
        // face with the memory path never crosses this boundary.
        test("the middle carries no execution face: no queue, no path"):
            val internal =
                Seq("queue_canAccept", "queue_flushRisk", "int_issue_valid", "mem_issue_valid")
            for port <- internal do assert(!ports.contains(port))

        test("the middle instantiates its two stages and the files they face"):
            assert(sv.contains("module Isu"))
            assert(sv.contains("module Wbu"))
            assert(sv.contains("module Arf"))
            assert(sv.contains("module CsrFile"))
            assert(sv.contains("module OperandRead"))
            for unit <- Seq("Exu", "Back", "Dispatch", "Completion", "Forward", "IntPath", "MemPath") do
                assert(!sv.contains(s"module $unit("))

        // The read resolves the network's columns and its own retiring writes: the offer comparisons and the waits are
        // the read's, and the middle knows nothing of the level — an offer that is not armed is all the wait there is.
        test("the read resolves the forward columns"):
            val read = TestFlow.ports(sv, "OperandRead")
            assert(read.contains("forward_arf_offers_0_armed") && read.contains("forward_arf_offers_1_armed"))
            assert(read.contains("forward_csr_offers_0_armed") && read.contains("forward_csr_offers_1_armed"))
            assert(read.contains("forward_commit_armed") && read.contains("arf_commit_bits_index"))
            assert(read.contains("csr_commit_bits_csr_type"))
            assert(sv.contains("~forward_arf_offers_0_armed"))

        // The stage registers are the middle's own — the units between them are combinational — and the IS/EX register
        // is not: it is the execute stage's input, registered by the back.
        test("the middle carries the ID/IS and EX/WB registers, not the IS/EX one"):
            assert(sv.contains("idisValid"))
            assert(sv.contains("exwbValid"))
            assert(!sv.contains("isexValid"))

        // The forward network's face is the read's to match: the middle carries the bus from the back's network to
        // the read, and the read takes the value of the first armed offer that names its register or CSR.
        test("the forward face reaches the read unit"):
            assert(sv.contains("forward_arf_offers_0_armed") && sv.contains("forward_arf_offers_1_armed"))
            assert(sv.contains("forward_csr_offers_0_armed") && sv.contains("forward_csr_offers_1_armed"))

        // The stage owns no register either: its two boundaries are the middle's, declared one level up. The read unit
        // is the only thing inside it, and the assembly is the stage's own wiring.
        test("the issue stage is purely combinational"):
            val start = sv.indexOf("module Isu(")
            assert(start >= 0)
            val body = sv.substring(start, sv.indexOf("endmodule", start))
            assert(!body.contains("always @(posedge"))

        // The commit is combinational too: its register is the middle's — declared beside the other boundary — so what
        // sits behind it owns no flop.
        test("the write-back stage is purely combinational"):
            val start = sv.indexOf("module Wbu(")
            assert(start >= 0)
            val body = sv.substring(start, sv.indexOf("endmodule", start))
            assert(!body.contains("always @(posedge"))
