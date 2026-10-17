package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{DecoupledIO, HardwareDataType}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The pipeline's cancel event, sourced at the commit point: `valid` is the flush itself, `next_pc` where the front
  * restarts. One event, one bus: a stage register's cancellation net and the fetcher's redirect are the same signal, so
  * the two halves cannot be wired apart.
  */
class FlushBus(width: Int) extends Bundle:
    val valid = Aligned(Bool())
    val next_pc = Aligned(UInt(width))

/** What a pipeline register does about the flush.
  *
  * Each case is correct for the register that declares it, and the difference is who the producer is:
  *   - [[Refuse]]: a handover in the flush cycle is refused — the producer waits a cycle. Right when the offer is a
  *     peer's: the handover is part of a stream that is being killed anyway, and accepting it would make the producer
  *     do work whose result is already known to be discarded (a fetcher would ask the bus for one more word of the dead
  *     path).
  *   - [[Consume]]: the handover is taken and dropped. Right when the producer is a device's response: a response
  *     cannot be left hanging — refusing it leaves it on the bus, where it blocks the next request until a later cycle
  *     consumes it, which costs the stream a cycle.
  *   - [[Ignore]]: the register does not obey the flush at all — it keeps its entry, presents it, and takes handovers
  *     as if nothing happened. Right for the register whose own entry *sources* the flush: that entry is the oldest,
  *     non-speculative instruction, and it must commit in the very cycle it cancels the younger stages.
  */
enum FlushHandover:
    case Refuse, Consume, Ignore

/** One-entry pipeline register between a producer's output (`in`) and a consumer's input (`out`).
  *
  * Both ends are referables, so the caller can pass module ports (the usual case) or internal wires. `flush` is this
  * register's cancellation net: the core broadcasts one, but whether a register obeys it is the caller's decision —
  * architectural state and anything that has to drain rather than vanish must not be wired to it. `name` is the stage
  * boundary this register is, and both registers carry it (its `Valid` and its bits): a stage register is read by name
  * from waveforms, event files and timing reports, so it says what it is instead of falling into the framework's
  * anonymous counter.
  *
  * A flushed entry never reaches the consumer, in either window: the entry the register holds is cleared at the edge
  * rather than presented, and what it takes in that cycle is dropped — no consumer needs to look at flush to be sure it
  * was not handed a stale entry. [[FlushHandover.Ignore]] is the exception, where there is no cancellation to speak of:
  * the register sources the net and goes on presenting.
  */
def pipelineConnect[T <: HardwareDataType](
    in: Referable[DecoupledIO[T]],
    out: Referable[DecoupledIO[T]],
    flush: Referable[FlushBus],
    name: String,
    onFlush: FlushHandover
)(using
    ClockScope,
    ResetScope,
    Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    InstanceContext
): Unit =
    val valid =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}Valid")
        RegInit(false.B)
    val bitsReg =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(name)
        Reg(in.bits.getType)

    // The one register that sources the net does not obey it ([[FlushHandover.Ignore]]), so for it the term is a
    // constant and folds away.
    val cancellation: Referable[Bool] = onFlush match
        case FlushHandover.Ignore => false.B
        case _                    => flush.valid

    val ready = !valid | out.ready
    in.ready := (onFlush match
        case FlushHandover.Refuse => ready & !flush.valid
        case _                    => ready
    )
    out.valid := valid & !cancellation
    out.bits :<= bitsReg

    val fire = in.valid & in.ready
    when(fire) {
        bitsReg :<= in.bits
    }

    // First match wins: cancellation beats a fill, which beats a drain.
    valid := muxCase(valid, Seq((cancellation, false.B), (fire, true.B), (out.valid & out.ready, false.B)))
