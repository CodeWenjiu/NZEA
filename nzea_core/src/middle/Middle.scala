package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.{ForwardBus, ForwardParameter, IssueSlot, IssueUop, RetireEvent}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{CsrType, Gpr, Isa}
import nzea_core.middle.isu.{IssueStage, IssueStageParameter}
import nzea_core.middle.rf.{Arf, ArfParameter, CsrFile, CsrFileParameter}
import nzea_core.middle.wbu.{Wbu, WbuParameter}
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.FlushBus
import nzea_sideband.CommitTrace
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class MiddleParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    lsqDepth: Int,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The forward bus's shape — the in-flight capacity's, shared with the network that broadcasts the column. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[MiddleParameter] = upickle.default.macroRW

class MiddleLayers(parameter: MiddleParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class MiddleIO(parameter: MiddleParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val decoded = Flipped(Decoupled(new IduOut(parameter.config)))
    val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))

    /** The retiring instruction, in program order: the completion without a queue, the queue's head with one. */
    val retire = Flipped(ValidIO(new RetireEvent(width)))

    /** The execute slot's pace: what the issue stage waits for before moving the next uop into IS/EX. */
    val slotRelease = Flipped(Bool())

    val forward = Aligned(new ForwardBus(width, parameter.offerCount))
    val commit = Aligned(ValidIO(new CommitTrace(width, Gpr.indexWidth, CsrType.addrWidth)))

    /** The flush event, out to the level tops: one bus they broadcast to every stage that obeys it. */
    val flush = Aligned(new FlushBus(width))

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: its two stages — [[nzea_core.middle.isu.IssueStage]] (the read and the assembly between the
  * middle's two elastic registers) and [[nzea_core.middle.wbu.Wbu]] (the retire register, the commit and the flush) —
  * and the two files they share.
  *
  * The register and CSR files live here, at the one place both stages face them: their read sides belong to the issue
  * stage, their write sides to the write-back stage. The module binds the stages to each other — the slot's destination
  * forward, the flush event across — wires the files' two sides, and presents the middle's boundary to the core: the
  * decoded stream in, the issue slot, the retirement, the forward bus and the commit/flush out. The two stages share
  * the `middle` scope, so the counters they declare land where they always have.
  */
@generator
object Middle extends Generator[MiddleParameter, MiddleLayers, MiddleIO, MiddleProbe]:
    override def moduleName(parameter: MiddleParameter): String = "Middle"

    /** The block name the middle's statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "middle"

    def architecture(parameter: MiddleParameter) =
        val io = summon[Interface[MiddleIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val issueStage =
            IssueStage.instantiate(
                IssueStageParameter(
                    c.isa,
                    c.defaultPc,
                    parameter.flow,
                    parameter.lsqDepth,
                    parameter.stats
                )
            )
        val wbu = Wbu.instantiate(WbuParameter(c.isa, c.defaultPc, parameter.flow, parameter.stats))
        val csr = CsrFile.instantiate(CsrFileParameter(c.width))
        val arf = Arf.instantiate(ArfParameter(c.width))

        issueStage.io.clock := io.clock
        issueStage.io.resetN := io.resetN
        wbu.io.clock := io.clock
        wbu.io.resetN := io.resetN
        csr.io.clock := io.clock
        csr.io.resetN := io.resetN
        arf.io.clock := io.clock
        arf.io.resetN := io.resetN

        // The decoded stream in, and the issued uop out.
        issueStage.io.decoded.valid := io.decoded.valid
        io.decoded.ready := issueStage.io.decoded.ready
        issueStage.io.decoded.bits :<= io.decoded.bits
        io.issue :<= issueStage.io.issue

        // The retirement: the write-back stage latches it, the issue stage paces on the release.
        wbu.io.retire :<= io.retire
        issueStage.io.slotRelease := io.slotRelease

        // Between the stages: the flush event comes back.
        issueStage.io.flush :<= wbu.io.flush

        // The files both stages face: the read side is the issue stage's, the write side is the retire register's.
        csr.io.read_addr := issueStage.io.slot_csr_addr
        issueStage.io.csr_rdata := csr.io.read_data
        arf.io.rs1.index := issueStage.io.arf_rs1.index
        issueStage.io.arf_rs1.data := arf.io.rs1.data
        arf.io.rs2.index := issueStage.io.arf_rs2.index
        issueStage.io.arf_rs2.data := arf.io.rs2.data
        arf.io.write :<= wbu.io.arf_write
        csr.io.write :<= wbu.io.csr_write

        // The forward bus, split at its ends: the column of offers and the retiring write's verdict on the issue side.
        issueStage.io.offers :<= io.forward.offers
        issueStage.io.commit_armed := io.forward.commit_armed
        // The retiring write is the write-back stage's own register, and the read is its forward: one register, seen by
        // the file's write port and by the read at once.
        issueStage.io.commit :<= wbu.io.arf_write

        // The boundary's outputs.
        io.flush :<= wbu.io.flush
        io.commit :<= wbu.io.commit
