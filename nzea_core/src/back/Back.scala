package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuConfig, BypassLevel, CoreConfig}
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.back.mem.{MemPath, MemPathParameter}
import nzea_core.isa.{Backend, Isa}
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BackParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    mmioRanges: Seq[String],
    bypass: BypassLevel,
    bpu: Option[BpuConfig],
    lsqDepth: Int,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The forward bus's shape — the in-flight capacity's, shared with the network that broadcasts the column. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[BackParameter] = upickle.default.macroRW

class BackLayers(parameter: BackParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BackIO(parameter: BackParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val issue = Flipped(new IssueSlot(new IssueUop(parameter.config)))

    /** The retiring instruction, in program order: the completion without a queue, the queue's head with one. */
    val retire = Aligned(ValidIO(new RetireEvent(width)))
    val forward = Flipped(new ForwardBus(width, parameter.offerCount))
    val dbus = Aligned(MemPath.dbusType(parameter.config))

    /** The execute slot's pace: what the middle waits for before moving the next uop in. The completion without a
      * queue; with one, the slot release — the memory request's acceptance, or a unit's completion — because a load's
      * slot is let go long before its answer lands.
      */
    val slotRelease = Aligned(Bool())

    /** The flush event the middle sources, back to the queue: it drops everything behind the retiring head. */
    val flush = Flipped(new FlushBus(width))

    /** The resolved branches, for the front's predictor: the int path's own registers, already off the resolve cone. */
    val bp_update = Aligned(ValidIO(new BpUpdate(width)))

class BackProbe(parameter: BackParameter) extends DVBundle[BackParameter, BackLayers](parameter)

/** Backend: the execute stage's units — it routes the issued uop to its path, merges the single completion, and hands
  * it to [[Completion]], which turns it into the middle's retire stream and slot pace.
  *
  * No pipeline state lives here outside the completion path: the middle holds the uop (its IS/EX register) and latches
  * what retires (its write-back register), so this module is the merge plus each path's own latency — the multiplier's
  * stages, the divider's loop, the memory's response. The routing test is the whole job at this level: which path owns
  * a uop is decode's `Backend`, so a path grows its own scheduler without this file noticing. Whether a queue is built
  * is [[Completion]]'s polymorphism, not this module's: the ports it wires are the same either way.
  */
@generator
object Back extends Generator[BackParameter, BackLayers, BackIO, BackProbe]:
    override def moduleName(parameter: BackParameter): String = "Back"

    /** The block name the backend's statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "back"

    def architecture(parameter: BackParameter) =
        val io = summon[Interface[BackIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val c = parameter.config
        val uopValid = io.issue.valid
        val uopFirst = io.issue.first
        val isMem = io.issue.bits.backend === Backend.MEM.lit.U(Backend.width)

        val int =
            IntPath.instantiate(
                IntPathParameter(
                    c.isa,
                    c.defaultPc,
                    parameter.flow,
                    parameter.stats.child(IntPath.Source),
                    parameter.bpu
                )
            )
        val mem = MemPath.instantiate(MemPathParameter(c.isa, c.defaultPc, parameter.mmioRanges))
        val forward =
            Forward.instantiate(ForwardParameter(c.isa, c.defaultPc, parameter.bypass, parameter.lsqDepth))
        val completion =
            Completion.instantiate(
                CompletionParameter(c.isa, c.defaultPc, parameter.flow, parameter.lsqDepth, parameter.stats)
            )

        int.io.clock := io.clock
        int.io.resetN := io.resetN
        mem.io.clock := io.clock
        mem.io.resetN := io.resetN
        forward.io.clock := io.clock
        forward.io.resetN := io.resetN
        completion.io.clock := io.clock
        completion.io.resetN := io.resetN
        io.dbus :<>= mem.io.dbus

        int.io.issue.valid := uopValid & !isMem
        int.io.issue.first := uopFirst & !isMem
        int.io.issue.bits :<= io.issue.bits
        mem.io.issue.valid := uopValid & isMem
        mem.io.issue.first := uopFirst & isMem
        mem.io.issue.bits :<= io.issue.bits

        // The resolved branches leave the back from here: the front's predictor listens, the middle does not pass them.
        io.bp_update :<= int.io.bp_update

        // The completion: a level for as long as the held uop is finished. It is what the queue writes an entry from,
        // and — without a queue — what retires and what frees the slot.
        val complete = (int.io.done & !isMem) | (mem.io.done & isMem)

        // The release: the slot's uop is finished with the slot. A memory op releases when the bus accepts its request
        // — a load's answer may still be in flight — an integer op when its unit reports, which is also its completion.
        val slotRelease = (int.io.done & !isMem) | (mem.io.slotRelease & isMem)

        val result = Wire(new PathResult(width))
        when(isMem) { result :<= mem.io.result }.otherwise { result :<= int.io.result }

        // What the completion path takes: the merged result, valid the cycle the held uop is finished.
        val finished = Wire(ValidIO(new PathResult(width)))
        finished.valid := complete
        finished.bits :<= result

        // The completion path: the queue when the configuration builds one, the pass-through otherwise. It owns the
        // retire stream's source, the slot's pace and the two gates the memory path's requests wait on, so the middle
        // sees the same contract either way.
        completion.io.issue :<= io.issue
        completion.io.release := slotRelease
        completion.io.complete :<= finished
        completion.io.reqTag := mem.io.reqTag
        completion.io.offset := mem.io.offset
        completion.io.resp :<= mem.io.resp
        completion.io.flush :<= io.flush

        io.slotRelease := completion.io.pace
        io.retire :<= completion.io.retire
        mem.io.canAccept := completion.io.canAccept
        mem.io.flushRisk := completion.io.flushRisk
        forward.io.entries :<>= completion.io.entries
        forward.io.head := completion.io.head

        // The forward network's raw producers: the completing instruction's result, with the destination it will write,
        // born here. The slot's own writer is the producer a read may have to wait for when it has no entry yet; the
        // retiring write is the middle's own and never crosses this boundary.
        forward.io.exec.valid := complete
        forward.io.exec.bits := result.data
        forward.io.slot.valid := io.issue.valid
        forward.io.slot.bits := io.issue.bits.rd_index
        io.forward.offers :<= forward.io.offers
        io.forward.commit_armed := forward.io.commit_armed
