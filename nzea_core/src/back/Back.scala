package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.back.mem.{MemPath, MemPathParameter}
import nzea_core.config.{BpuConfig, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.{pipelineConnect, FlushBus, FlushHandover, ForwardOffer}
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

    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[BackParameter] = upickle.default.macroRW

class BackLayers(parameter: BackParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BackIO(parameter: BackParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    val moved = Flipped(Decoupled(new ExecSlot(parameter.config)))
    val flush = Flipped(new FlushBus(width))
    val retire = Aligned(Decoupled(new RetireEvent(width)))
    val forward = Aligned(new ForwardBus(width, parameter.offerCount))

    /** The data bus the memory path drives, and the resolved branches for the front's predictor: the training port
      * follows the predictor's config, so a design without one carries neither the wires nor the registers behind them.
      */
    val dbus = Aligned(MemPath.dbusType(parameter.config))

    val bp_update: Option[BundleField[ValidIO[BpUpdate]]] =
        Option.when(parameter.bpu.isDefined)(Aligned(ValidIO(new BpUpdate(width))))

class BackProbe(parameter: BackParameter) extends DVBundle[BackParameter, BackLayers](parameter)

/** The back: the execution subsystem — the execute slot it registers, the dispatch that routes the issued uop to its
  * path, the two paths, the completion path and the forward network. It owns no architectural state: the register and
  * CSR files are the middle's, and what the execute stage needs of them arrives resolved in the slot — both reads are
  * the issue stage's.
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

        val slot = Wire(Decoupled(new ExecSlot(c)))
        pipelineConnect(io.moved, slot, io.flush, "isex", FlushHandover.Refuse)
        slot.ready := completion.io.pace

        if parameter.flow.counters then
            val busy =
                backStats.stat("slot_busy", Regions.Throughput, "cycles the execute slot holds an unfinished uop")
            busy.inc(slot.valid & !slot.ready)

        val slotFirst = RegInit(false.B)
        slotFirst := io.moved.fire

        val view = Wire(new IssueSlot(new ExecSlot(c)))

        // The CSR code rides only where the form names the CSR (the read's own gate): nothing is remasked on the way
        // out, so a memory op's address field cannot be read as one.
        view.valid := slot.valid
        view.first := slotFirst & !io.flush.valid
        view.bits :<= slot.bits

        dispatch.io.issue :<= view
        completion.io.issue :<= view
        forward.io.issue :<= view

        int.io.path :<>= dispatch.io.int
        mem.io.path :<>= dispatch.io.mem

        completion.io.merged :<>= dispatch.io.merged

        // The only live value a read can take is the integer path's: a memory op's live answer is never forwardable — a
        // load's data lands after its slot has moved on, where its queue entry answers the read, and a store writes
        // nothing. That is what keeps the memory path's request cone (the tag, the strobe, the response select) out of
        // the read's own loop.
        forward.io.exec.valid := dispatch.io.int.done
        forward.io.exec.bits :<= dispatch.io.int.result

        forward.io.csr_offer :<= dispatch.io.int.csr_offer

        completion.io.flush :<= io.flush
        mem.io.queue :<>= completion.io.queue
        io.retire :<>= completion.io.retire

        forward.io.view :<>= completion.io.view
        io.forward :<= forward.io.forward

        io.dbus :<>= mem.io.dbus
        io.bp_update.zip(int.io.bp_update).foreach((port, path) => port :<= path)
