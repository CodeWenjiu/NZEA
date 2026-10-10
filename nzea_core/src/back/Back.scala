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
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
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

    /** The forward column's length — the in-flight capacity's shape, shared with the read that consumes it. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[BackParameter] = upickle.default.macroRW

class BackLayers(parameter: BackParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BackIO(parameter: BackParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The middle's face, flipped: the read's uop in, the flush it obeys, and the verdicts out. */
    val moved = Flipped(Decoupled(new ExecSlot(parameter.config)))
    val flush = Flipped(new FlushBus(width))
    val retire = Aligned(Decoupled(new RetireEvent(width)))
    val forward = Aligned(new ForwardBus(width, parameter.offerCount))

    /** The core's own: the data bus the memory path drives, and the resolved branches for the front's predictor — the
      * training port follows the predictor's config, so a design without one carries neither the wires nor the
      * registers behind them.
      */
    val dbus = Aligned(MemPath.dbusType(parameter.config))

    val bp_update: Option[BundleField[ValidIO[BpUpdate]]] =
        Option.when(parameter.bpu.isDefined)(Aligned(ValidIO(new BpUpdate(width))))

class BackProbe(parameter: BackParameter) extends DVBundle[BackParameter, BackLayers](parameter)

/** The back: the execution subsystem — the execute slot it registers, the dispatch that routes the issued uop to its
  * path, the two paths themselves, the completion path that holds what the slot cannot, and the forward network that
  * gathers the in-flight writers. It owns no architectural state: the register and CSR files are the middle's, and what
  * the execute stage needs of them arrives resolved in the slot — both reads are the issue stage's.
  *
  * Its face with the middle is the pipeline's own vocabulary: the read's uop comes in and is registered here (the
  * execute stage's input is its own), and what goes back is what the middle's stages wait on — the retiring record and
  * the forward network's face. Nothing else of the back crosses this boundary: the dispatch and the merge, the queue's
  * face with the memory path, the tags and the flush risk are this module's own wiring, so a back-end feature — a
  * cache, a deeper queue, a reservation station — adds nothing to the middle's ports.
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

        // The wiring below follows the uop: the register it waits in, the view the units read, the dispatch that routes
        // it, the merge that concludes it, and the two readers of that conclusion. Each edge that runs against that flow
        // is named where it is made — the completion path's pace into the register, the queue's face with the memory
        // path, the queue's view into the forward network.

        // ── The IS/EX register, and the uop view the units read ──
        // The execute stage's input, registered where it is consumed — the middle hands the read's uop over and this
        // module is the register's fill side. Its room is the completion path's pace, which the queue's own room may
        // hold back after a unit has already reported.
        val slot = Wire(Decoupled(new ExecSlot(c)))
        pipelineConnect(io.moved, slot, io.flush, "isex", FlushHandover.Refuse)
        slot.ready := completion.io.pace

        /** The execute slot's occupancy: the IS/EX register's own, declared here beside the register whose two ends are
          * this module's. Its sibling `operand_wait` is the issue stage's, beside the read it watches — and the two sit
          * in the pipeline order their registers do.
          */
        if parameter.flow.counters then
            val busy =
                backStats.stat("slot_busy", Regions.Throughput, "cycles the execute slot holds an unfinished uop")
            busy.inc(slot.valid & !slot.ready)

        // The execute slot's arrival edge, for the per-instruction events the units key off `first`.
        val slotFirst = RegInit(false.B)
        slotFirst := io.moved.fire

        val view = Wire(new IssueSlot(new IssueUop(c)))

        /** The uop the units work on, as the slot presents it: the payload reversed into the backend's contract, the
          * CSR read it carries included. A memory instruction's `inst[31:20]` was an address offset, not a CSR address,
          * so its code is none, and a flush silences the roles — the units take a vanished uop as abandoned.
          */
        def issueView(): Unit =
            val bits = slot.bits
            val isMem = bits.backend === Backend.MEM.lit.U(Backend.width)
            view.valid := slot.valid
            view.first := slotFirst & !io.flush.valid
            view.bits.pc := bits.pc
            view.bits.next_pc := bits.next_pc
            view.bits.fall_through := bits.fall_through
            view.bits.pht_count := bits.pht_count
            view.bits.imm := bits.imm
            view.bits.src_a := bits.src_a
            view.bits.src_b := bits.src_b
            view.bits.src_c := bits.src_c
            view.bits.backend := bits.backend
            view.bits.op := bits.op
            view.bits.rd_index := bits.rd_index
            view.bits.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), bits.csr_type)
            view.bits.csr_rdata := bits.csr_rdata

        issueView()

        // ── The uop's three readers ──
        // One view, three readers: the dispatch decides its path, the completion path writes an entry from it and takes
        // the destination a retire record needs, and the forward network names it as the youngest writer in flight.
        dispatch.io.issue :<= view
        completion.io.issue :<= view
        forward.io.issue :<= view

        // ── The dispatch and the two paths ──
        // The one place the issued uop's path is decided, and the faces that decision drives — the routing and the merge
        // are one `is_mem` test.
        int.io.path :<>= dispatch.io.int
        mem.io.path :<>= dispatch.io.mem

        // ── The merge, and its two readers ──
        // The conclusion about the held uop goes to the completion path whole — the value, and the release before the
        // queue's room.
        completion.io.merged :<>= dispatch.io.merged

        // The forward network's youngest writer is the slot's uop, and the only live value a read can take is the
        // integer path's: a memory op's live answer is never a forwardable one — a load's data lands after its slot has
        // moved on, where its queue entry answers the read, and a store writes nothing — so the memory path's result is
        // not a source here. That is what keeps its request cone (the tag, the strobe, the response select) out of the
        // read's own loop: the youngest writer's value is an integer cone alone.
        forward.io.exec.valid := dispatch.io.int.done
        forward.io.exec.bits :<= dispatch.io.int.result

        // The sysu's offer is the same uop in the CSR's index space: the value it leaves behind, reported on the path's
        // face and armed into the CSR column's youngest slot.
        forward.io.csr_offer :<= dispatch.io.int.csr_offer

        // ── The completion path, and the queue's face with the memory path ──
        // The retire stream the middle waits on, and the queue's own face with the memory path — the one face of the
        // queue that does not leave this module.
        completion.io.flush :<= io.flush
        mem.io.queue :<>= completion.io.queue
        io.retire :<>= completion.io.retire

        // ── The forward network: the in-flight writers, gathered into the columns the middle's read resolves ──
        forward.io.view :<>= completion.io.view
        io.forward :<= forward.io.forward

        // ── The core's own: the data bus, and the resolved branches for the front's predictor ──
        // The training port is the int path's, present exactly where the predictor it trains is.
        io.dbus :<>= mem.io.dbus
        io.bp_update.zip(int.io.bp_update).foreach((port, path) => port :<= path)
