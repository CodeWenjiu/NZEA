package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.mem.{DbusUser, LsqResponse, LsuData, LsuOp, MemOp}
import nzea_core.isa.{Backend, Gpr, Isa}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The queue's parameter: its depth is a power of two, and the queue is only built with a nonzero one — a design
  * without a queue builds no `Lsq` at all, so this parameter never spells absence.
  *
  * A power of two because the ring's pointers wrap by their own width (`addWrap`): that is what makes the wrap equal to
  * `mod depth`, and the `(index - head) mod depth` ordering the bypass reads relies on the same identity. A depth of
  * one is refused rather than special-cased: its ring degenerates to zero-width pointers, and its behavior is the
  * pass-through a design without a queue already gets.
  */
case class LsqParameter(isa: Isa, defaultPc: Long, depth: Int, flow: RtlFlow, stats: StatScope) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)
    require(depth >= 2, s"lsq depth must be >= 2 — a depth of one is the pass-through, spelled by building no queue")
    require((depth & (depth - 1)) == 0, s"lsq depth must be a power of two (the ring's pointers wrap by their width)")

given upickle.default.ReadWriter[LsqParameter] = upickle.default.macroRW

class LsqLayers(parameter: LsqParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** One queue entry: the retire record it will present when it reaches the head, whether it is finished, whether the
  * slot is occupied, and what the memory path needs to route a load's answer back to it.
  *
  * `done` is the entry's own completion: an integer op is done when it is written, a store when its write is accepted,
  * a load when its tagged response lands. `tag` is that response's key, `offset` and `op` are what the load's value is
  * selected and extended with — the answer arrives after the execute slot has moved on, so the entry is the only place
  * that still knows how to read the bus word.
  */
class LsqEntry(config: CoreConfig) extends Bundle:
    val record = Aligned(new RetireEvent(config.width))
    val done = Aligned(Bool())
    val valid = Aligned(Bool())
    val tag = Aligned(UInt(DbusUser.tagWidth))
    val offset = Aligned(UInt(LsuData.offsetWidth(config.width)))
    val op = Aligned(UInt(LsuOp.set(config.isa).width))

/** What the bypass network sees of one entry: whether it is occupied, whether its value is ready, and the write it
  * offers. The queue exports this instead of the whole entry so the network's ports carry only what it reads.
  */
class LsqView(width: Int) extends Bundle:
    val valid = Aligned(Bool())
    val done = Aligned(Bool())
    val rd_index = Aligned(UInt(Gpr.indexWidth))
    val data = Aligned(UInt(width))

/** The queue as the forward network reads it: every entry's view, and the head's position — the order a multiple match
  * is resolved in (the youngest wins).
  */
class QueueView(width: Int, depth: Int) extends Bundle:
    val entries = Aligned(Vec(depth, new LsqView(width)))
    val head = Aligned(UInt(log2Ceil(depth)))

class LsqIO(parameter: LsqParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    private val depth = parameter.depth
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The execute slot's uop: what a new entry's retire record is filled from, and whether it is a memory op. */
    val issue = Flipped(new IssueSlot(new IssueUop(parameter.config)))

    /** The completing path's result: the values a new entry is written with. */
    val complete = Flipped(ValidIO(new PathResult(width)))

    /** The slot release: the execute slot's uop is finished with the slot and takes an entry. The memory path's
      * acceptance for a memory op, the unit's completion for an integer one.
      */
    val slotRelease = Flipped(Bool())

    /** The memory path's request side, for a new entry: the tag its answer will carry, and the in-word offset a load's
      * value will be selected with.
      */
    val reqTag = Flipped(UInt(DbusUser.tagWidth))
    val offset = Flipped(UInt(LsuData.offsetWidth(width)))

    /** The data bus's answers, routed to the entry whose tag they carry. */
    val resp = Flipped(ValidIO(new LsqResponse(width)))

    /** The flush event the middle's write-back stage sources: everything behind the retiring head is dropped. */
    val flush = Flipped(new FlushBus(width))

    /** The queue's room for one more entry: the memory path holds its request until an entry exists for it. */
    val canAccept = Aligned(Bool())

    /** Whether a mispredicted branch sits anywhere in the queue: no bus access may leave the core while one does. A
      * branch older than the access would flush it after the fact, and neither a store's write nor a load's request can
      * be taken back then — the store's data would be wrong, the load's access could fault or touch MMIO.
      */
    val flushRisk = Aligned(Bool())

    /** The retirement head: the record the middle retires, in program order. */
    val retire = Aligned(ValidIO(new RetireEvent(width)))

    /** The entries as the bypass network reads them, and the head's position — the order the network resolves a
      * multiple match in (the youngest wins).
      */
    val entries = Aligned(Vec(depth, new LsqView(width)))
    val head = Aligned(UInt(log2Ceil(depth)))

class LsqProbe(parameter: LsqParameter) extends DVBundle[LsqParameter, LsqLayers](parameter)

/** The load/store queue: the core's in-order completion queue, one entry per instruction in flight.
  *
  * The execute slot is a single-cycle residence for everything the units can finish in a cycle, and an entry is what
  * outlives it: an instruction is written into the queue when the slot releases it (the memory path's acceptance, or a
  * unit's completion) and retires from the head, in program order, once its entry is done. Between the two it is the
  * only record of the instruction — the write-back value, the commit's fields, the prediction check — so a load can
  * release the slot while its answer is still on the bus and the instructions behind it can keep issuing. That is the
  * whole point of the structure: the slot no longer waits for the load's data.
  *
  * Entries are done in one of three ways: an integer op when it is written (the unit's result, same cycle), a store
  * when the bus accepts its write (non-speculative — see [[flushRisk]]), a load when its tagged answer arrives. The
  * answer is routed by tag, never by position: responses may interleave across slaves, and a flushed load's answer must
  * drain without completing anything — a stale tag finds no occupied, unfinished entry.
  *
  * Retirement is the head's: its record goes out as the [[RetireEvent]] the middle commits, the entry is popped, and a
  * mispredicted branch's flush drops everything behind it in the same cycle (the queue cannot roll back, so it never
  * retires a speculative instruction in the first place).
  */
@generator
object Lsq extends Generator[LsqParameter, LsqLayers, LsqIO, LsqProbe]:
    override def moduleName(parameter: LsqParameter): String = "Lsq"

    /** The block name the queue's statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "lsq"

    def architecture(parameter: LsqParameter) =
        val io = summon[Interface[LsqIO]]
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

        val entries = Reg(Vec(depth, new LsqEntry(parameter.config)))
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

        /** The store-issue gate: a store's write must not be on the bus while a branch older than the store could still
          * resolve mispredicted and flush it — a flush after the fact cannot take the write back. A branch's own entry
          * records the check the moment it is written (`is_branch & next_pc =/= pred_next_pc`), so this is the OR over
          * the queue. A store whose older branch is correctly predicted is not gated by it: a flush needs the branch to
          * be wrong, and a wrong one retires as a flush — killing the store before its write ever leaves.
          */
        def flushRisk(): Referable[Bool] =
            (0 until depth)
                .map(index => entries.ref(index).valid & entries.ref(index).record.mispredicted)
                .foldLeft(false.B: Referable[Bool])(_ | _)
        io.flushRisk := flushRisk()

        // The head's own state: what retires this cycle, and what a push and a pop do to the ring.
        val push = io.slotRelease
        val headValid = entries.ref(head).valid
        val headDone = entries.ref(head).done
        val pop = headValid & headDone & !io.flush.valid
        io.retire.valid := pop
        io.retire.bits :<= entries.ref(head).record

        // One update each: a push and a pop in the same cycle net out in the count instead of one overwriting the
        // other, and the flush's own clearing comes last.
        count := addWrap(
            count,
            subWrap(push ? (1.U(cntW), 0.U(cntW)), pop ? (1.U(cntW), 0.U(cntW)), cntW),
            cntW
        )

        /** One instruction's entry, written whole when the slot releases it: the retire record from the completion, the
          * destination from the held uop, and the memory path's tag and offset for a load's later answer. A load's
          * entry starts unfinished and without a value — its data is not on the bus yet — so `data` is zeroed rather
          * than taken from the result, whose data belongs to whatever answer is on the bus this cycle.
          */
        def writeEntry(): Unit =
            val record = RetireEvent.of(width, io.complete.bits, io.issue.bits.rd_index)
            when(push) {
                for index <- 0 until depth do
                    when(tail === index.U(ptrW)) {
                        entries.ref(index).record :<= record
                        entries.ref(index).record.data := io.complete.valid ? (io.complete.bits.data, 0.U(width))
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
          * occupied, unfinished entries; a flushed load's entry is unoccupied and a store's is done, so a stale answer
          * completes nothing.
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

        /** The bypass network's view: what a read can take from the queue, entry by entry. */
        def exportEntries(): Unit =
            for index <- 0 until depth do
                io.entries.ref(index).valid := entries.ref(index).valid
                io.entries.ref(index).done := entries.ref(index).done
                io.entries.ref(index).rd_index := entries.ref(index).record.rd_index
                io.entries.ref(index).data := entries.ref(index).record.data
            io.head := head

        writeEntry()
        answerLoad()
        retireHead()
        clearOnFlush()
        exportEntries()

        /** Counters the simulator reads back: how full the queue runs, and how often its head holds the retire back.
          * Declared here, where the pointers are.
          */
        def counters(): Unit =
            val full = parameter.stats.stat("lsq_full", Regions.Throughput, "cycles the queue cannot take an entry")
            val busy = parameter.stats.stat("lsq_busy", Regions.Throughput, "cycles the queue holds an instruction")
            val blocked =
                parameter.stats.stat("lsq_blocked", Regions.Throughput, "cycles the head waits for its value")
            full.inc(count === depth.U(cntW))
            busy.inc(count =/= 0.U(cntW))
            blocked.inc(headValid & !headDone & !io.flush.valid)

        if parameter.flow.counters then counters()
