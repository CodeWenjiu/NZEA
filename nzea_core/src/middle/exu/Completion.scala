package nzea_core.middle.exu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.{IssueSlot, IssueUop, QueueFace, RetireEvent}
import nzea_core.isa.{Gpr, Isa}
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The completion path's parameter: the design's, plus the queue's depth — a power of two, or zero for no queue, which
  * is the pass-through. `stats` is the scope the queue's counters are attributed to — this module adds no segment of
  * its own, so they keep the names they had beside the paths (`back.lsq`).
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

    /** The execute slot's uop: the destination a retire record takes, and what the queue writes an entry from. */
    val issue = Flipped(new IssueSlot(new IssueUop(parameter.config)))

    /** The merge's conclusion: the completing value, and the release before the queue's room. */
    val merged = Flipped(new Merged(width))

    /** The queue's own face, with the memory path: its verdicts go out, the request side comes in — the middle's own
      * view of the face, so the boundary and the completion path are the same bundle.
      */
    val queue = Aligned(new QueueFace(parameter.config))

    /** The flush event the middle sources: everything behind the retiring head is dropped. */
    val flush = Flipped(new FlushBus(width))

    /** The pace the middle sees: the release, held while the queue has no room for the entry it would take. Without a
      * queue it is the completion itself — the slot then waits for the answer, which is the pre-queue behavior.
      */
    val pace = Aligned(Bool())

    /** The retiring instruction, in program order: the queue's head, or the completion itself without one. */
    val retire = Aligned(ValidIO(new RetireEvent(width)))

    /** The queue as the forward network reads it. */
    val view = Aligned(new QueueView(width, entryCount))

class CompletionProbe(parameter: CompletionParameter) extends DVBundle[CompletionParameter, CompletionLayers](parameter)

/** The completion path: what a finished instruction becomes before the middle retires it — the queue when the
  * configuration builds one, the completion itself otherwise.
  *
  * Every instruction passes through here, memory or not: the queue is the core's in-order completion queue, not a
  * memory structure. An integer op's record lives in it so a slow load in front of it holds retirement back, its
  * `mispredicted` fact is what gates the younger bus accesses, and its result is what the bypass network offers a read
  * behind it. Without a queue the completion passes straight through, which is why the middle's contract — one retire
  * stream and one slot pace — does not move with the configuration.
  *
  * The queue changes what the middle is told, not how a path runs: the memory path still holds its request and the
  * units still report, and only the release's pace and the retire stream's source differ. The two configurations are
  * this module's whole polymorphism, so the middle around it wires the same ports either way.
  *
  * This module is where the queue's existence stops: the shapes it hands the rest of the design are the same in both
  * configurations, and they are worth naming together, because these are what moved when the queue was introduced —
  * they are architecture, not configuration:
  *   - the retire stream is a record ([[RetireEvent]]): the value, the destination, the CSR facts, the MMIO mark, and
  *     the per-instruction `mispredicted` fact the bus-issue gate reads;
  *   - the slot's pace is a signal of its own (`pace`), separate from the completion — a load's slot is let go long
  *     before its answer lands;
  *   - the memory path asks the completion path for room and for the bus-issue gate (`canAccept` / `flushRisk`);
  *   - the bypass network reads the entries (`entries` / `head`) — a constant view when no queue is built — and the
  *     data bus's tag width outlasts the accesses a queue can keep in flight.
  * Everything else the queue needs — the entry, the tag match, the ordering — lives inside it.
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

        /** The completion path with a queue: the entry takes the completion's record, the pace waits for the queue's
          * room, and the retire stream and the bus-issue gate come from the queue.
          */
        def withQueue(depth: Int): Unit =
            val lsq =
                Lsq.instantiate(
                    LsqParameter(
                        parameter.config.isa,
                        parameter.config.defaultPc,
                        depth,
                        parameter.flow,
                        parameter.stats.child(Lsq.Source)
                    )
                )
            lsq.io.clock := io.clock
            lsq.io.resetN := io.resetN
            lsq.io.issue :<= io.issue
            lsq.io.complete :<= io.merged.complete
            lsq.io.reqTag := io.queue.reqTag
            lsq.io.offset := io.queue.offset
            lsq.io.resp :<= io.queue.resp
            lsq.io.flush :<= io.flush

            // A release takes an entry, so a full queue holds the slot — and with it the pace the middle sees.
            io.pace := io.merged.release & lsq.io.canAccept
            lsq.io.slotRelease := io.pace

            io.queue.canAccept := lsq.io.canAccept
            io.queue.flushRisk := lsq.io.flushRisk
            io.retire :<= lsq.io.retire
            io.view.entries :<= lsq.io.entries
            io.view.head := lsq.io.head

        /** The completion path without one: the completion is the pace the middle moves at and the retire itself, and
          * the request's gates are always open. The entries' view is a constant — the bypass network keeps one port
          * shape, and a constant folds out of whoever reads it.
          */
        def passThrough(): Unit =
            io.pace := io.merged.complete.valid
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

        if parameter.lsqDepth == 0 then passThrough() else withQueue(parameter.lsqDepth)
