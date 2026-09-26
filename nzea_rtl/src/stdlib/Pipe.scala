package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.stdlib.HardwareDataType
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** `Decoupled` plus a `flush` input, the handshake the core's functional units speak.
  *
  * `flush` is an input like `ready`: it is driven by the downstream consumer (the commit/flush source drives every pipe
  * flush in the design), which is what lets a cancelled uop be dropped without a round trip.
  */
class PipeIO[T <: HardwareDataType](_bits: T)(using TypeImpl, ConstructorApi) extends Bundle:
  val valid = Aligned(summon[ConstructorApi].Bool())
  val ready = Flipped(summon[ConstructorApi].Bool())
  val bits = Aligned(_bits)
  val flush = Flipped(summon[ConstructorApi].Bool())

object PipeIO:
  def apply[T <: HardwareDataType](bits: T)(using TypeImpl, ConstructorApi): PipeIO[T] = new PipeIO(bits)

/** One-entry pipeline register between a producer's output (`in`) and a consumer's input (`out`).
  *
  * Both ends are referables, so the caller can pass module ports (the usual case) or internal wires holding a `PipeIO`.
  * `flush` is taken from `out` and propagated to `in`, so one signal reaches producer, register and consumer.
  */
def pipelineConnect[T <: HardwareDataType](
    in: Referable[PipeIO[T]],
    out: Referable[PipeIO[T]]
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
  in.flush := out.flush

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
  valid := muxCase(valid, Seq((out.flush, false.B), (fire, true.B), (out.valid & out.ready, false.B)))
