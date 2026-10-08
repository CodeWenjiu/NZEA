package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.{ForwardParameter, IssueSlot, IssueUop, RetireEvent}
import nzea_core.config.CoreConfig
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_core.middle.isu.{Isu, IsuParameter}
import nzea_core.middle.rf.{Arf, ArfParameter, CsrFile, CsrFileParameter}
import nzea_core.middle.wbu.{Wbu, WbuParameter}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.{pipelineConnect, FlushBus, FlushHandover, ForwardOffer}
import nzea_sideband.CommitTrace
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The middle's parameter: the design's, the queue's depth — the shape the forward column takes — and the scope its
  * counters hang under. `stats` is the scope the middle sits in, not the middle's own: the module owns the `middle`
  * segment for the stages, so a caller hands over where the block is placed and not the names inside it.
  */
case class MiddleParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    lsqDepth: Int,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The forward column's length — the in-flight capacity's shape, shared with the back's network. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[MiddleParameter] = upickle.default.macroRW

class MiddleLayers(parameter: MiddleParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class MiddleIO(parameter: MiddleParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val decoded = Flipped(Decoupled(new IduOut(parameter.config)))

    /** The back's face: the execute slot's uop as the backend's contract, and the verdicts it hands back — the slot's
      * pace, the retiring record, the forward column, and the level's verdict for the retiring write.
      */
    val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))
    val pace = Flipped(Bool())
    val retire = Flipped(ValidIO(new RetireEvent(width)))
    val offers = Flipped(Vec(parameter.offerCount, new ForwardOffer(width, Gpr.indexWidth)))
    val commit_armed = Flipped(Bool())

    val commit = Aligned(ValidIO(new CommitTrace(width, Gpr.indexWidth, CsrType.addrWidth)))

    /** The flush event, out to the level tops: one bus they broadcast to every stage that obeys it. */
    val flush = Aligned(new FlushBus(width))

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: the three stages — [[nzea_core.middle.isu.Isu]], the execute slot and [[nzea_core.middle.wbu.Wbu]]
  * — the elastic registers between them, and the files they face.
  *
  * [[nzea_core.middle.isu.Isu]] is the combinational read and assembly; the execute slot is the IS/EX register, whose
  * read side is presented to the back as the backend's uop — the middle holds no unit of its own there, and what the
  * back hands back is what its stages wait on (the slot's pace, the retire stream, the forward column); and
  * [[nzea_core.middle.wbu.Wbu]] is the retire register that sources the commit and the flush. The register and CSR
  * files sit here, where the stages face them.
  *
  * The stage boundaries are declared here, beside the front-end's own (`pipelineConnect`, one per boundary): the units
  * inside the stages are combinational, and every register the pipeline has is one this module names. The rest is the
  * wiring: the flush forward, the retirement, the first cycle and the column back, the files' two sides — and the
  * middle's boundary to the core: the decoded stream in, the back's face, the commit and the flush out. The stages
  * share the `middle` scope, so the counters they declare land where they always have, and the one that is the
  * register's own is declared here beside it.
  */
@generator
object Middle extends Generator[MiddleParameter, MiddleLayers, MiddleIO, MiddleProbe]:
    override def moduleName(parameter: MiddleParameter): String = "Middle"

    /** The block name the middle's stages' statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "middle"

    def architecture(parameter: MiddleParameter) =
        val io = summon[Interface[MiddleIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val c = parameter.config
        val middleStats = parameter.stats.child(Middle.Source)

        val isu =
            Isu.instantiate(IsuParameter(c.isa, c.defaultPc, parameter.flow, parameter.lsqDepth, middleStats))

        // The execute slot: the IS/EX register's read side, which the back's face reads — its room is the back's pace,
        // which the queue's own room may hold back after a unit has already reported.
        val slot = Wire(Decoupled(new ExecSlot(c)))

        /** The execute slot's occupancy: the IS/EX register's own, declared here beside the register whose two ends are
          * this module's. Its sibling `operand_wait` is the issue stage's, beside the read it watches — and the
          * declaration sits between the two stages so the schema reads in pipeline order.
          */
        if parameter.flow.counters then
            val busy =
                middleStats.stat("slot_busy", Regions.Throughput, "cycles the execute slot holds an unfinished uop")
            busy.inc(slot.valid & !slot.ready)

        // The execute slot's arrival edge, for the per-instruction events the units key off `first`.
        val slotFirst = RegInit(false.B)
        slotFirst := isu.io.moved.fire

        val wbu = Wbu.instantiate(WbuParameter(c.isa, c.defaultPc, parameter.flow, middleStats))
        val csr = CsrFile.instantiate(CsrFileParameter(c.width))
        val arf = Arf.instantiate(ArfParameter(c.width))

        isu.io.clock := io.clock
        isu.io.resetN := io.resetN
        wbu.io.clock := io.clock
        wbu.io.resetN := io.resetN
        csr.io.clock := io.clock
        csr.io.resetN := io.resetN
        arf.io.clock := io.clock
        arf.io.resetN := io.resetN

        // The middle's two elastic registers — ID/IS and IS/EX — declared here as the front-end declares its own: the
        // stages' units are combinational, and the boundaries between them are this module's. Each register sits
        // between two stages and never inside one: the issue stage fills the IS/EX register and the execute stage reads
        // it — the register's read side is the slot wire above.
        pipelineConnect(io.decoded, isu.io.decoded, wbu.io.flush, "idis", FlushHandover.Refuse)
        pipelineConnect(isu.io.moved, slot, wbu.io.flush, "isex", FlushHandover.Refuse)
        slot.ready := io.pace

        /** The uop the back works on, as the slot presents it: the payload reversed into the backend's contract, with
          * the CSR read answered here. A memory instruction's `inst[31:20]` is an address offset, not a CSR address, so
          * it maps to none, and a flush silences the roles — the units take a vanished uop as abandoned.
          */
        def issueView(): Unit =
            val bits = slot.bits
            val isMem = bits.backend === Backend.MEM.lit.U(Backend.width)
            io.issue.valid := slot.valid
            io.issue.first := slotFirst & !io.flush.valid
            io.issue.bits.pc := bits.pc
            io.issue.bits.next_pc := bits.next_pc
            io.issue.bits.fall_through := bits.fall_through
            io.issue.bits.pht_count := bits.pht_count
            io.issue.bits.imm := bits.imm
            io.issue.bits.src_a := bits.src_a
            io.issue.bits.src_b := bits.src_b
            io.issue.bits.src_c := bits.src_c
            io.issue.bits.backend := bits.backend
            io.issue.bits.op := bits.op
            io.issue.bits.rd_index := bits.rd_index
            io.issue.bits.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), CsrType.fromAddr(bits.csr_addr))
            io.issue.bits.csr_rdata := csr.io.read.data
            csr.io.read.addr := bits.csr_addr

        issueView()

        // The back's verdicts: the retire stream the write-back stage latches, and the column and the level's verdict
        // the read resolves against.
        wbu.io.retire :<= io.retire
        isu.io.offers :<= io.offers
        isu.io.commit_armed := io.commit_armed

        // The files both stages face: the register file's read side is the issue stage's, the CSR one's is the execute
        // stage's — the slot carries the address — and the write side is the retire register's. The reader is the
        // destination side of a read port (its index flows out of it), so the stage's port is the connect's
        // destination — a submodule's output port cannot be one.
        isu.io.arf_rs1 :<>= arf.io.rs1
        isu.io.arf_rs2 :<>= arf.io.rs2
        arf.io.write :<= wbu.io.arf_write
        csr.io.write :<= wbu.io.csr_write

        // The retiring write is the write-back stage's own register, and the read is its forward: one register, seen by
        // the file's write port and by the read at once.
        isu.io.commit :<= wbu.io.arf_write

        // The boundary's outputs.
        io.flush :<= wbu.io.flush
        io.commit :<= wbu.io.commit
