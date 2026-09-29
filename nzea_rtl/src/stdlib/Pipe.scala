package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{DecoupledIO, HardwareDataType}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What a pipeline register does with a handover offered in the cycle `flush` is asserted.
  *
  * Both are correct for the register that declares them, and the difference is who the producer is:
  *   - [[Refuse]]: the producer waits a cycle. Right when the offer is a peer's — the handover is part of a stream that
  *     is being killed anyway, and accepting it would make the producer do work whose result is already known to be
  *     discarded (a fetcher would ask the bus for one more word of the dead path).
  *   - [[Consume]]: the handover is taken and dropped. Right when the producer is a device's response: a response
  *     cannot be left hanging — refusing it leaves it on the bus, where it blocks the next request until a later cycle
  *     consumes it, which costs the stream a cycle.
  */
enum FlushHandover:
  case Refuse, Consume

/** One-entry pipeline register between a producer's output (`in`) and a consumer's input (`out`).
  *
  * Both ends are referables, so the caller can pass module ports (the usual case) or internal wires. `flush` is this
  * register's cancellation net: the core broadcasts one, but whether a register obeys it is the caller's decision —
  * architectural state and anything that has to drain rather than vanish must not be wired to it. `name` is the stage
  * boundary this register is, and the two registers carry it (its `Valid` and its bits): a stage register is read by
  * name from waveforms, event files and timing reports, so it says what it is instead of falling into the framework's
  * anonymous counter. `onFlush` is how a handover in the flush cycle is treated — see [[FlushHandover]].
  *
  * A flushed entry never reaches the consumer, in either window: the entry the register holds is cleared at the edge
  * rather than presented, and what it takes in that cycle is dropped. That is the whole of the register's cancellation
  * — no consumer needs to look at flush to be sure it was not handed a stale entry, because an entry carries no
  * generation of its own once it has left the bus.
  */
def pipelineConnect[T <: HardwareDataType](
    in: Referable[DecoupledIO[T]],
    out: Referable[DecoupledIO[T]],
    flush: Referable[Bool],
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

  val ready = !valid | out.ready
  in.ready := (onFlush match
    case FlushHandover.Refuse  => ready & !flush
    case FlushHandover.Consume => ready
  )
  out.valid := valid & !flush
  out.bits :<= bitsReg

  val fire = in.valid & in.ready
  when(fire) {
    bitsReg :<= in.bits
  }

  // First match wins: a flush clears, an accepted input fills, a consumed output drains, otherwise hold.
  valid := muxCase(valid, Seq((flush, false.B), (fire, true.B), (out.valid & out.ready, false.B)))
