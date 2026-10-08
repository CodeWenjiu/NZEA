package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.back.mem.{MemPath, MemPathParameter}
import nzea_core.config.{BpuConfig, BypassLevel, CoreConfig}
import nzea_core.isa.{Gpr, Isa}
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.{FlushBus, ForwardOffer}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BackParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    bypass: BypassLevel,
    bpu: Option[BpuConfig],
    lsqDepth: Int,
    mmioRanges: Seq[String],
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The forward column's length — the in-flight capacity's shape, shared with the read that consumes it. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[BackParameter] = upickle.default.macroRW

class BackLayers(parameter: BackParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BackIO(parameter: BackParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The middle's face, flipped: the execute slot's uop in, the flush it obeys, and the verdicts out. */
    val issue = Flipped(new IssueSlot(new IssueUop(parameter.config)))
    val flush = Flipped(new FlushBus(width))
    val pace = Aligned(Bool())
    val retire = Aligned(ValidIO(new RetireEvent(width)))
    val offers = Aligned(Vec(parameter.offerCount, new ForwardOffer(width, Gpr.indexWidth)))
    val commit_armed = Aligned(Bool())

    /** The core's own: the data bus the memory path drives, and the resolved branches for the front's predictor. */
    val dbus = Aligned(MemPath.dbusType(parameter.config))
    val bp_update = Aligned(ValidIO(new BpUpdate(width)))

class BackProbe(parameter: BackParameter) extends DVBundle[BackParameter, BackLayers](parameter)

/** The back: the execution subsystem — the dispatch that routes an issued uop to its path, the two paths themselves,
  * the completion path that holds what the execute slot cannot, and the forward network that gathers the in-flight
  * writers.
  *
  * Its face with the middle is the pipeline's own vocabulary: the execute slot's uop goes down, and what comes back is
  * what the middle waits on — the slot's pace, the retire stream, the forward column, and the level's verdict for the
  * retiring write. Nothing else of the back crosses this boundary: the dispatch and the merge, the queue's face with
  * the memory path, the tags and the flush risk are this module's own wiring, so a back-end feature — a cache, a deeper
  * queue, a reservation station — adds nothing to the middle's ports.
  *
  * The core's two ports are here as well: the data bus the memory path drives, and the resolved branches the front's
  * predictor trains on — the path that resolves them is this module's, the table that consumes them is not.
  */
@generator
object Back extends Generator[BackParameter, BackLayers, BackIO, BackProbe]:
    override def moduleName(parameter: BackParameter): String = "Back"

    /** The block name the back's statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "back"

    def architecture(parameter: BackParameter) =
        val io = summon[Interface[BackIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val backStats = parameter.stats.child(Back.Source)

        val dispatch = Dispatch.instantiate(DispatchParameter(c.isa, c.defaultPc))
        val completion =
            Completion.instantiate(
                CompletionParameter(c.isa, c.defaultPc, parameter.flow, parameter.lsqDepth, backStats)
            )
        val forward =
            Forward.instantiate(ForwardParameter(c.isa, c.defaultPc, parameter.bypass, parameter.lsqDepth))
        val int =
            IntPath.instantiate(
                IntPathParameter(c.isa, c.defaultPc, parameter.flow, backStats.child(IntPath.Source), parameter.bpu)
            )
        val mem = MemPath.instantiate(MemPathParameter(c.isa, c.defaultPc, parameter.mmioRanges))

        dispatch.io.clock := io.clock
        dispatch.io.resetN := io.resetN
        completion.io.clock := io.clock
        completion.io.resetN := io.resetN
        forward.io.clock := io.clock
        forward.io.resetN := io.resetN
        int.io.clock := io.clock
        int.io.resetN := io.resetN
        mem.io.clock := io.clock
        mem.io.resetN := io.resetN

        // The dispatch: the one place the issued uop's path is decided, and the faces that decision drives — the
        // routing and the merge are one `is_mem` test.
        dispatch.io.issue :<= io.issue
        int.io.path :<>= dispatch.io.int
        mem.io.path :<>= dispatch.io.mem

        // The completion path: the entry a release writes, the retire stream, and the queue's face with the memory
        // path — the one face of the queue that does not leave this module.
        completion.io.issue :<= io.issue
        completion.io.merged :<>= dispatch.io.merged
        completion.io.flush :<= io.flush
        mem.io.queue :<>= completion.io.queue

        // The forward network: the in-flight writers, gathered into the column the middle's read resolves.
        forward.io.exec :<= dispatch.io.merged.complete
        forward.io.issue :<= io.issue
        forward.io.view :<>= completion.io.view

        // What the middle waits on: the slot's pace, the retiring record, the column, and the level's verdict.
        io.pace := completion.io.pace
        io.retire :<= completion.io.retire
        io.offers :<= forward.io.offers
        io.commit_armed := forward.io.commit_armed

        // The core's own: the data bus, and the resolved branches for the front's predictor.
        io.dbus :<>= mem.io.dbus
        io.bp_update :<= int.io.bp_update
