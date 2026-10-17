package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.isa.{CsrType, Gpr, Isa}
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The completion path's parameter: the design's, plus the queue's depth — a power of two, or zero for no queue, which
  * is the pass-through. `stats` is the scope the queue's counters are attributed to; this module adds no segment of its
  * own, so they land under the queue's own (`back.completion_queue`).
  */
case class CompletionParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, lsqDepth: Int, stats: StatScope)
    extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[CompletionParameter] = upickle.default.macroRW

class CompletionLayers(parameter: CompletionParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class CompletionIO(parameter: CompletionParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    private val entryCount = if parameter.lsqDepth == 0 then 1 else parameter.lsqDepth
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    val issue = Flipped(new IssueSlot(new ExecSlot(parameter.config)))

    val merged = Flipped(new Merged(width))

    val queue = Aligned(new QueueFace(parameter.config))

    val flush = Flipped(new FlushBus(width))

    /** The pace the middle sees: the release, held while the queue has no room for the entry it would take. Without a
      * queue it is the completion itself — the slot then waits for the answer.
      */
    val pace = Aligned(Bool())

    val retire = Aligned(Decoupled(new RetireEvent(width)))

    val view = Aligned(new QueueView(width, entryCount))

class CompletionProbe(parameter: CompletionParameter) extends DVBundle[CompletionParameter, CompletionLayers](parameter)

/** The completion path: what a finished instruction becomes before the middle retires it — the queue when the
  * configuration builds one, the completion itself otherwise.
  *
  * Every instruction passes through here, memory or not: the queue is the core's in-order completion queue, not a
  * memory structure. The middle's contract — one retire stream and one slot pace — is the same in both configurations,
  * so it does not move with the configuration.
  *
  * Two shapes are worth naming, because they are architecture rather than configuration: the slot's pace is a signal of
  * its own, separate from the completion (a load's slot is let go long before its answer lands), and the data bus's tag
  * width outlasts the accesses a queue can keep in flight.
  */
@generator
object Completion extends Generator[CompletionParameter, CompletionLayers, CompletionIO, CompletionProbe]:
    override def moduleName(parameter: CompletionParameter): String = "Completion"

    def architecture(parameter: CompletionParameter) =
        val io = summon[Interface[CompletionIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val entryCount = if parameter.lsqDepth == 0 then 1 else parameter.lsqDepth

        def withQueue(depth: Int): Unit =
            val completionQueue =
                CompletionQueue.instantiate(
                    CompletionQueueParameter(
                        parameter.config.isa,
                        parameter.config.defaultPc,
                        depth,
                        parameter.flow,
                        parameter.stats.child(CompletionQueue.Source)
                    )
                )
            completionQueue.io.clock := io.clock
            completionQueue.io.resetN := io.resetN
            completionQueue.io.issue :<= io.issue
            completionQueue.io.complete :<= io.merged.complete
            completionQueue.io.reqTag := io.queue.reqTag
            completionQueue.io.offset := io.queue.offset
            completionQueue.io.resp :<= io.queue.resp
            completionQueue.io.flush :<= io.flush

            // A release takes an entry, so a full queue holds the slot — and with it the pace the middle sees.
            io.pace := io.merged.release & completionQueue.io.canAccept
            completionQueue.io.slotRelease := io.pace

            io.queue.canAccept := completionQueue.io.canAccept
            io.queue.flushRisk := completionQueue.io.flushRisk
            io.retire :<>= completionQueue.io.retire
            io.view.entries :<= completionQueue.io.entries
            io.view.head := completionQueue.io.head

        /** Without a queue: the completion is the pace the middle moves at and the retire itself, and the request's
          * gates are always open. The entries' view is a constant, so the network keeps one port shape either way.
          */
        def passThrough(): Unit =
            // The completion is the retire stream itself here, so the slot is let go only when the middle takes the
            // record: the pace is the handshake's.
            io.pace := io.merged.complete.valid & io.retire.ready
            io.queue.canAccept := true.B
            io.queue.flushRisk := false.B
            io.retire.valid := io.merged.complete.valid
            io.retire.bits :<= RetireEvent.of(width, io.merged.complete.bits, io.issue.bits.rd_index)
            io.view.head := 0.U(log2Ceil(entryCount))
            for index <- 0 until entryCount do
                io.view.entries.ref(index).valid := false.B
                io.view.entries.ref(index).done := false.B
                io.view.entries.ref(index).rd_index := 0.U(Gpr.indexWidth)
                io.view.entries.ref(index).data := 0.U(width)
                io.view.entries.ref(index).csr_type := 0.U(CsrType.width)
                io.view.entries.ref(index).csr_data := 0.U(width)

        if parameter.lsqDepth == 0 then passThrough() else withQueue(parameter.lsqDepth)
