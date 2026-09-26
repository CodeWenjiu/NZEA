package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{DecoupledIO, HardwareDataType}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One-entry pipeline register between a producer's output (`in`) and a consumer's input (`out`).
  *
  * Both ends are referables, so the caller can pass module ports (the usual case) or internal wires. `flush` is this
  * register's cancellation net: the core broadcasts one, but whether a register obeys it is the caller's decision —
  * architectural state and anything that has to drain rather than vanish must not be wired to it.
  *
  * A flushed entry never reaches the consumer, in either window: the entry the register holds is not presented while
  * flush is asserted (and is cleared at the edge), and one handed over in that same cycle is dropped rather than
  * loaded. That is the whole of the register's cancellation — no consumer needs to look at flush to be sure it was not
  * handed a stale entry, because an entry carries no generation of its own once it has left the bus.
  */
def pipelineConnect[T <: HardwareDataType](
    in: Referable[DecoupledIO[T]],
    out: Referable[DecoupledIO[T]],
    flush: Referable[Bool]
)(using
    ClockScope,
    ResetScope,
    Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Unit =
  val valid = RegInit(false.B)
  val bitsReg = Reg(in.bits.getType)

  in.ready := !valid | out.ready
  out.valid := valid & !flush
  out.bits :<= bitsReg

  val fire = in.valid & in.ready
  when(fire) {
    bitsReg :<= in.bits
  }

  // First match wins: a flush clears, an accepted input fills, a consumed output drains, otherwise hold.
  valid := muxCase(valid, Seq((flush, false.B), (fire, true.B), (out.valid & out.ready, false.B)))
