package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.{ExecSlot, ForwardBus, ForwardParameter, RetireEvent}
import nzea_core.config.CoreConfig
import nzea_core.front.idu.IduOut
import nzea_core.isa.{CsrType, Gpr, Isa}
import nzea_core.middle.isu.{Isu, IsuParameter}
import nzea_core.middle.rf.{Arf, ArfParameter, CsrFile, CsrFileParameter}
import nzea_core.middle.wbu.{Wbu, WbuParameter}
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.{pipelineConnect, FlushBus, FlushHandover}
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

    /** The back's face: the read's uop out, and the verdicts in — the retiring record and the forward network's face
      * ([[nzea_core.back.ForwardBus]]).
      */
    val moved = Aligned(Decoupled(new ExecSlot(parameter.config)))
    val retire = Flipped(ValidIO(new RetireEvent(width)))
    val forward = Flipped(new ForwardBus(width, parameter.offerCount))

    val commit = Aligned(ValidIO(new CommitTrace(width, Gpr.indexWidth, CsrType.addrWidth)))

    /** The flush event, out to the level tops: one bus they broadcast to every stage that obeys it. */
    val flush = Aligned(new FlushBus(width))

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: the instruction stream's two ends — [[nzea_core.middle.isu.Isu]] and [[nzea_core.middle.wbu.Wbu]] —
  * the elastic register between them, and the register and CSR files they face.
  *
  * [[nzea_core.middle.isu.Isu]] is the combinational read and assembly; [[nzea_core.middle.wbu.Wbu]] is the retire
  * register that sources the commit and the flush. Between them the stream leaves the module: the read's uop crosses to
  * the back, which registers it as its own input, executes it, and hands back what these two stages wait on — the
  * slot's pace is the register's room there, the retire stream is the completion path's, the columns are the network's.
  * The two files sit here, where both stages face them: the read sides are the issue stage's and the write sides the
  * retire register's.
  *
  * The stage boundaries are declared here, beside the front-end's own (`pipelineConnect`, one per boundary): the units
  * inside the stages are combinational, and every register the pipeline has is one the module that consumes it names —
  * the middle names ID/IS, the back names IS/EX, the write-back register is [[nzea_core.middle.wbu.Wbu]]'s. The rest is
  * the wiring: the flush forward, the retirement, the columns back, the files' sides — and the middle's boundary to the
  * core: the decoded stream in, the back's face, the commit and the flush out.
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

        val c = parameter.config
        val middleStats = parameter.stats.child(Middle.Source)

        val isu =
            Isu.instantiate(IsuParameter(c.isa, c.defaultPc, parameter.flow, parameter.lsqDepth, middleStats))
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

        // The middle's elastic register — ID/IS — declared here as the front-end declares its own: the stages' units are
        // combinational, and the boundary between them is this module's. The IS/EX register is not: it is the execute
        // stage's own input, registered by the back, and this module hands the read's uop over as the register's fill
        // side — its room comes back on `moved.ready`.
        pipelineConnect(io.decoded, isu.io.decoded, wbu.io.flush, "idis", FlushHandover.Refuse)
        io.moved :<>= isu.io.moved

        // The back's verdicts: the retiring record the write-back stage latches, and the network's face the read
        // resolves against.
        wbu.io.retire :<= io.retire
        isu.io.forward :<= io.forward

        // The two files both stages face: both read sides are the issue stage's — the register file's two ports and the
        // CSR's one — and both write sides are the retire register's. The reader is the destination side of a read port
        // (its index flows out of it), so the stage's port is the connect's destination — a submodule's output port
        // cannot be one.
        isu.io.arf_rs1 :<>= arf.io.rs1
        isu.io.arf_rs2 :<>= arf.io.rs2
        isu.io.csr_read :<>= csr.io.read
        arf.io.write :<= wbu.io.arf_write
        csr.io.write :<= wbu.io.csr_write

        // The retiring writes are the write-back stage's own register, and the reads are their forward: one register,
        // seen by the files' write ports and by the read at once.
        isu.io.arf_commit :<= wbu.io.arf_write
        isu.io.csr_commit :<= wbu.io.csr_write

        // The boundary's outputs.
        io.flush :<= wbu.io.flush
        io.commit :<= wbu.io.commit
