package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.{IntFace, MemFace, QueueFace}
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{CsrType, Gpr, Isa}
import nzea_core.middle.exu.{Exu, ExuParameter}
import nzea_core.middle.isu.{Isu, IsuParameter}
import nzea_core.middle.rf.{Arf, ArfParameter, CsrFile, CsrFileParameter}
import nzea_core.middle.wbu.{Wbu, WbuParameter}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.{pipelineConnect, FlushBus, FlushHandover}
import nzea_sideband.CommitTrace
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The middle's parameter: the design's, the forwarding level and the queue's depth — the shape the execute stage takes
  * — and the scope its counters hang under. `stats` is the scope the middle sits in, not the middle's own: the module
  * owns the `middle` segment for the stages and the `back` one for the execute stage, so a caller hands over where the
  * block is placed and not the names inside it.
  */
case class MiddleParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    bypass: BypassLevel,
    lsqDepth: Int,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[MiddleParameter] = upickle.default.macroRW

class MiddleLayers(parameter: MiddleParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class MiddleIO(parameter: MiddleParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val decoded = Flipped(Decoupled(new IduOut(parameter.config)))

    /** The execution face: the two paths' faces and the queue's, in the same shapes the paths themselves carry. */
    val int = Aligned(new IntFace(parameter.config))
    val mem = Aligned(new MemFace(parameter.config))
    val queue = Aligned(new QueueFace(parameter.config))

    val commit = Aligned(ValidIO(new CommitTrace(width, Gpr.indexWidth, CsrType.addrWidth)))

    /** The flush event, out to the level tops: one bus they broadcast to every stage that obeys it. */
    val flush = Aligned(new FlushBus(width))

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: the three stages — [[nzea_core.middle.isu.Isu]], [[nzea_core.middle.exu.Exu]] and
  * [[nzea_core.middle.wbu.Wbu]] — the elastic registers between them, and the files they face.
  *
  * [[nzea_core.middle.isu.Isu]] is the combinational read and assembly; [[nzea_core.middle.exu.Exu]] holds the slot's
  * read side and the face to the execution paths, with the dispatch, the completion path and the forward network behind
  * it; and [[nzea_core.middle.wbu.Wbu]] is the retire register that sources the commit and the flush. The register and
  * CSR files sit here, where the stages face them.
  *
  * The stage boundaries are declared here, beside the front-end's own (`pipelineConnect`, one per boundary): the units
  * inside the stages are combinational, and every register the pipeline has is one this module names. The rest is the
  * wiring: the flush forward, the retirement, the first cycle and the column back, the files' two sides — and the
  * middle's boundary to the core: the decoded stream in, the three faces, the commit and the flush out. The stages
  * share the `middle` scope; the execute stage keeps the `back` one, so the counters they declare land where they
  * always have, and the one that is the register's own is declared here beside it.
  */
@generator
object Middle extends Generator[MiddleParameter, MiddleLayers, MiddleIO, MiddleProbe]:
    override def moduleName(parameter: MiddleParameter): String = "Middle"

    /** The block name the middle's stages' statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "middle"

    /** The block name the execute stage's statistics are attributed to — the segment the paths and the queue keep from
      * the module they were split out of. The names a user reads (`back.lsq`, `back.int`) do not move with the split.
      */
    val BackSource: String = "back"

    def architecture(parameter: MiddleParameter) =
        val io = summon[Interface[MiddleIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val c = parameter.config
        val middleStats = parameter.stats.child(Middle.Source)
        val backStats = parameter.stats.child(Middle.BackSource)

        val isu =
            Isu.instantiate(IsuParameter(c.isa, c.defaultPc, parameter.flow, parameter.lsqDepth, middleStats))
        val exu =
            Exu.instantiate(
                ExuParameter(c.isa, c.defaultPc, parameter.flow, parameter.bypass, parameter.lsqDepth, backStats)
            )

        /** The execute slot's occupancy: the IS/EX register's own, declared here beside the register whose two ends are
          * this module's. Its sibling `operand_wait` is the issue stage's, beside the read it watches — and the
          * declaration sits between the two stages so the schema reads in pipeline order.
          */
        if parameter.flow.counters then
            val busy =
                middleStats.stat("slot_busy", Regions.Throughput, "cycles the execute slot holds an unfinished uop")
            busy.inc(exu.io.slot.valid & !exu.io.slot.ready)

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
        exu.io.clock := io.clock
        exu.io.resetN := io.resetN

        // The middle's two elastic registers — ID/IS and IS/EX — declared here as the front-end declares its own: the
        // stages' units are combinational, and the boundaries between them are this module's. Each register sits
        // between two stages and never inside one: the issue stage fills the IS/EX register and the execute stage reads
        // it.
        pipelineConnect(io.decoded, isu.io.decoded, wbu.io.flush, "idis", FlushHandover.Refuse)
        pipelineConnect(isu.io.moved, exu.io.slot, wbu.io.flush, "isex", FlushHandover.Refuse)
        // The execute slot's arrival edge, for the per-instruction events the units key off `first`.
        val slotFirst = RegInit(false.B)
        slotFirst := isu.io.moved.fire

        // The execution face, up to the core: three faces, one connection each.
        io.int :<>= exu.io.int
        io.mem :<>= exu.io.mem
        io.queue :<>= exu.io.queue

        // The stages: the flush crosses into the execute stage, the retirement, the first cycle and the forward column
        // come back out of it.
        exu.io.flush :<= wbu.io.flush
        wbu.io.retire :<= exu.io.retire
        exu.io.slot_first := slotFirst

        // The files both stages face: the register file's read side is the issue stage's, the CSR one's is the execute
        // stage's — the slot carries the address — and the write side is the retire register's. The reader is the
        // destination side of a read port (its index flows out of it), so the stage's port is the connect's
        // destination — a submodule's output port cannot be one.
        isu.io.arf_rs1 :<>= arf.io.rs1
        isu.io.arf_rs2 :<>= arf.io.rs2
        exu.io.csr_read :<>= csr.io.read
        arf.io.write :<= wbu.io.arf_write
        csr.io.write :<= wbu.io.csr_write

        // The forward column's consumer, and the retiring write's verdict for it.
        isu.io.offers :<= exu.io.offers
        isu.io.commit_armed := exu.io.commit_armed
        // The retiring write is the write-back stage's own register, and the read is its forward: one register, seen by
        // the file's write port and by the read at once.
        isu.io.commit :<= wbu.io.arf_write

        // The boundary's outputs.
        io.flush :<= wbu.io.flush
        io.commit :<= wbu.io.commit
