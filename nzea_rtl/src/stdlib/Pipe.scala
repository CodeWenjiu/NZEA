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
  * Both ends are referables, so the caller can pass module ports (the usual case) or internal wires. `flush` is the
  * core's cancellation broadcast — one net every stage reads directly, rather than something a stage asks its consumer
  * for, so no stage's cancellation depends on the stage below it.
  *
  * A flushed entry never reaches the consumer, even when it was handed over in the same cycle: flush has priority over
  * the load.
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
  out.valid := valid
  out.bits :<= bitsReg

  val fire = in.valid & in.ready
  when(fire) {
    bitsReg :<= in.bits
  }

  // First match wins: a flush clears, an accepted input fills, a consumed output drains, otherwise hold.
  valid := muxCase(valid, Seq((flush, false.B), (fire, true.B), (out.valid & out.ready, false.B)))
