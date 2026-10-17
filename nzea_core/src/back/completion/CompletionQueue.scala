package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.mem.{DbusUser, LsuData, LsuOp, MemOp, QueueResponse}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The queue's parameter: its depth is a power of two, and the queue is only built with a nonzero one — a design
  * without a queue builds no `CompletionQueue` at all, so this parameter never spells absence.
  *
  * A power of two because the ring's pointers wrap by their own width (`addWrap`), which is what makes the wrap equal
  * to `mod depth` — the same identity the bypass reads' `(index - head) mod depth` ordering rests on.
  */
case class CompletionQueueParameter(isa: Isa, defaultPc: Long, depth: Int, flow: RtlFlow, stats: StatScope)
    extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)
    require(depth >= 2, s"queue depth must be >= 2 — a depth of one is the pass-through, spelled by building no queue")
    require((depth & (depth - 1)) == 0, s"queue depth must be a power of two (the ring's pointers wrap by their width)")

given upickle.default.ReadWriter[CompletionQueueParameter] = upickle.default.macroRW

class CompletionQueueLayers(parameter: CompletionQueueParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** One queue entry: the retire record it will present when it reaches the head, and what the memory path needs to route
  * a load's answer back to it.
  *
  * `done` is the entry's own completion: an integer op is done when it is written, a store when its write is accepted,
  * a load when its tagged response lands. `tag` is that response's key, `offset` and `op` are what the load's value is
  * selected and extended with — the answer arrives after the execute slot has moved on, so the entry is the only place
  * that still knows how to read the bus word.
  */
class CompletionQueueEntry(config: CoreConfig) extends Bundle:
    val record = Aligned(new RetireEvent(config.width))
    val done = Aligned(Bool())
    val valid = Aligned(Bool())
    val tag = Aligned(UInt(DbusUser.tagWidth))
    val offset = Aligned(UInt(LsuData.offsetWidth(config.width)))
    val op = Aligned(UInt(LsuOp.set(config.isa).width))

/** What the bypass network sees of one entry: the queue exports a view instead of the whole entry, so the network's
  * ports carry only what it reads.
  */
class QueueEntryView(width: Int) extends Bundle:
    val valid = Aligned(Bool())
    val done = Aligned(Bool())
    val rd_index = Aligned(UInt(Gpr.indexWidth))
    val data = Aligned(UInt(width))
    val csr_type = Aligned(UInt(CsrType.width))
    val csr_data = Aligned(UInt(width))

/** The queue as the forward network reads it: every entry's view, and the head's position — the order a multiple match
  * is resolved in (the youngest wins).
  */
class QueueView(width: Int, depth: Int) extends Bundle:
    val entries = Aligned(Vec(depth, new QueueEntryView(width)))
    val head = Aligned(UInt(log2Ceil(depth)))

class CompletionQueueIO(parameter: CompletionQueueParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    private val depth = parameter.depth
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    val issue = Flipped(new IssueSlot(new ExecSlot(parameter.config)))

    val complete = Flipped(ValidIO(new PathResult(width)))

    /** The slot release: the execute slot's uop is finished with the slot and takes an entry — the memory path's
      * acceptance for a memory op, the unit's completion for an integer one.
      */
    val slotRelease = Flipped(Bool())

    val reqTag = Flipped(UInt(DbusUser.tagWidth))
    val offset = Flipped(UInt(LsuData.offsetWidth(width)))

    val resp = Flipped(ValidIO(new QueueResponse(width)))

    /** The flush event the middle's write-back stage sources: everything behind the retiring head is dropped. */
    val flush = Flipped(new FlushBus(width))

    val canAccept = Aligned(Bool())

    /** Whether a mispredicted branch sits anywhere in the queue: no bus access may leave the core while one does. A
      * branch older than the access would flush it after the fact, and neither a store's write nor a load's request can
      * be taken back then — the store's data would be wrong, the load's access could fault or touch MMIO.
      */
    val flushRisk = Aligned(Bool())

    val retire = Aligned(Decoupled(new RetireEvent(width)))

    val entries = Aligned(Vec(depth, new QueueEntryView(width)))
    val head = Aligned(UInt(log2Ceil(depth)))

class CompletionQueueProbe(parameter: CompletionQueueParameter)
    extends DVBundle[CompletionQueueParameter, CompletionQueueLayers](parameter)

/** The completion queue: the core's in-order record of instructions that have executed but not retired — one entry per
  * instruction in flight.
  *
  * The execute slot is a single-cycle residence for everything the units can finish in a cycle, and an entry is what
  * outlives it: an instruction is written when the slot releases it and retires from the head, in program order, once
  * its entry is done. Between the two the entry is the only record of the instruction, so a load can release the slot
  * while its answer is still on the bus.
  *
  * The answer is routed by tag, never by position: responses may interleave across slaves, and a flushed load's answer
  * must drain without completing anything — a stale tag finds no occupied, unfinished entry. Retirement is the head's,
  * and a mispredicted branch's flush drops everything behind it in the same cycle: the queue cannot roll back, so it
  * never retires a speculative instruction in the first place.
  */
@generator
object CompletionQueue
    extends Generator[CompletionQueueParameter, CompletionQueueLayers, CompletionQueueIO, CompletionQueueProbe]:
    override def moduleName(parameter: CompletionQueueParameter): String = "CompletionQueue"

    /** The block name the queue's statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "completion_queue"

    def architecture(parameter: CompletionQueueParameter) =
        val io = summon[Interface[CompletionQueueIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val depth = parameter.depth
        val ptrW = log2Ceil(depth)
        val cntW = log2Ceil(depth + 1)

        val lsuOps = LsuOp.set(parameter.config.isa)
        val memOps = MemOp.set(parameter.config.isa)
        val lsuOp: Node[UInt] = memOps.payload(io.issue.bits.op)
        val isMem = io.issue.bits.backend === Backend.MEM.lit.U(Backend.width)

        val entries = Reg(Vec(depth, new CompletionQueueEntry(parameter.config)))
        when(!io.resetN.asBool) {
            for index <- 0 until depth do
                entries.ref(index).valid := false.B
                entries.ref(index).done := false.B
        }

        val head = RegInit(0.U(ptrW))
        val tail = RegInit(0.U(ptrW))
        val count = RegInit(0.U(cntW))
        val headNext = addWrap(head, 1.U(ptrW), ptrW)
        val tailNext = addWrap(tail, 1.U(ptrW), ptrW)

        /** The room for one more entry: the memory path holds a request until an entry exists for its answer, and the
          * back holds the slot's pace on it too, so a release never overwrites an occupied entry.
          */
        io.canAccept := count < depth.U(cntW)

        def flushRisk(): Referable[Bool] =
            (0 until depth)
                .map(index => entries.ref(index).valid & entries.ref(index).record.mispredicted)
                .foldLeft(false.B: Referable[Bool])(_ | _)
        io.flushRisk := flushRisk()

        val push = io.slotRelease
        val headValid = entries.ref(head).valid
        val headDone = entries.ref(head).done
        val pop = headValid & headDone & !io.flush.valid & io.retire.ready
        io.retire.valid := pop
        io.retire.bits :<= entries.ref(head).record

        // One update each: a push and a pop in the same cycle net out in the count instead of one overwriting the
        // other, and the flush's own clearing comes last.
        count := addWrap(
            count,
            subWrap(push ? (1.U(cntW), 0.U(cntW)), pop ? (1.U(cntW), 0.U(cntW)), cntW),
            cntW
        )

        /** One instruction's entry, written whole when the slot releases it. A load's entry starts unfinished, and the
          * answer writes its `data` when it arrives; the value the completion carries at the release is don't-care
          * until then, because a read takes a value only from an armed offer. So the record's data is written through,
          * not gated to zero: the gate would put the completion's valid — and the whole load-hit cone behind it — on
          * the entry's data path for every instruction.
          */
        def writeEntry(): Unit =
            val record = RetireEvent.of(width, io.complete.bits, io.issue.bits.rd_index)
            when(push) {
                for index <- 0 until depth do
                    when(tail === index.U(ptrW)) {
                        entries.ref(index).record :<= record
                        entries.ref(index).done := io.complete.valid
                        entries.ref(index).valid := true.B
                        entries.ref(index).tag := io.reqTag
                        entries.ref(index).offset := io.offset
                        entries.ref(index).op := lsuOp
                    }
                tail := tailNext
            }

        /** The load's answer: the bus word selected and extended with the matching entry's own offset and op — the
          * answer arrives after the slot moved on, so the entry is where that knowledge lives. The match is by tag over
          * occupied, unfinished entries, so a stale answer completes nothing.
          */
        def answerLoad(): Unit =
            val hits = (0 until depth).map { index =>
                entries.ref(index).valid & !entries.ref(index).done & (entries.ref(index).tag === io.resp.bits.tag)
            }
            // The hits are mutually exclusive (a tag names one request), so a first-match select is the whole mux.
            val selectedOffset: Referable[UInt] =
                muxCase(0.U(LsuData.offsetWidth(width)), (0 until depth).map(i => (hits(i), entries.ref(i).offset)))
            val selectedOp: Referable[UInt] =
                muxCase(0.U(lsuOps.width), (0 until depth).map(i => (hits(i), entries.ref(i).op)))
            val value = LsuData.loadWord(width, io.resp.bits.data, selectedOffset, selectedOp, lsuOps)
            when(io.resp.valid) {
                for index <- 0 until depth do
                    when(hits(index)) {
                        entries.ref(index).record.data := value.asUInt
                        entries.ref(index).done := true.B
                    }
            }

        /** Retirement: the head's record leaves and its entry is popped. A flush cycle retires nothing — the head at
          * that point is on the path being killed — so the pop and the drop never overlap.
          */
        def retireHead(): Unit =
            when(pop) {
                for index <- 0 until depth do when(head === index.U(ptrW)) { entries.ref(index).valid := false.B }
                head := headNext
            }

        /** The flush: everything behind the retiring head is dropped. The head itself retired in the cycle before — the
          * flush is the middle's, one register past this queue's own retire — so the queue simply empties from the
          * head.
          */
        def clearOnFlush(): Unit =
            when(io.flush.valid) {
                for index <- 0 until depth do entries.ref(index).valid := false.B
                tail := head
                count := 0.U(cntW)
            }

        def exportEntries(): Unit =
            for index <- 0 until depth do
                io.entries.ref(index).valid := entries.ref(index).valid
                io.entries.ref(index).done := entries.ref(index).done
                io.entries.ref(index).rd_index := entries.ref(index).record.rd_index
                io.entries.ref(index).data := entries.ref(index).record.data
                io.entries.ref(index).csr_type := entries.ref(index).record.csr_type
                io.entries.ref(index).csr_data := entries.ref(index).record.csr_data
            io.head := head

        writeEntry()
        answerLoad()
        retireHead()
        clearOnFlush()
        exportEntries()

        def counters(): Unit =
            val full = parameter.stats.stat("full", Regions.Throughput, "cycles the queue cannot take an entry")
            val busy = parameter.stats.stat("busy", Regions.Throughput, "cycles the queue holds an instruction")
            val blocked =
                parameter.stats.stat("blocked", Regions.Throughput, "cycles the head waits for its value")
            full.inc(count === depth.U(cntW))
            busy.inc(count =/= 0.U(cntW))
            blocked.inc(headValid & !headDone & !io.flush.valid)

        if parameter.flow.counters then counters()
