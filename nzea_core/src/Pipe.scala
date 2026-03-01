package nzea_core

import chisel3._
import chisel3.util.{DecoupledIO, MuxCase, RegEnable}

/** PipeIO: extends DecoupledIO with flush. Inherits fire (valid && ready).
  * flush: Input (like ready), driven by downstream (e.g. WBU). Flush source
  * drives all pipe flush inputs.
  */
class PipeIO[T <: Data](gen: T) extends DecoupledIO(gen) {
  val flush = Input(Bool())
}

object PipeIO {
  def apply[T <: Data](gen: T): PipeIO[T] = new PipeIO(gen)
}

/** PipelineConnect: connects pipeline register between in (producer) and out
  * (consumer). Caller provides both ends; PipelineConnect gets flush from
  * out.flush and propagates to in.flush.
  */
object PipelineConnect {
  def apply[T <: Data](in: PipeIO[T], out: PipeIO[T]): Unit = {
    in.flush := out.flush
    val currentValid = RegInit(false.B)

    in.ready := !currentValid || out.ready
    out.valid := currentValid
    out.bits := RegEnable(in.bits, in.fire)

    currentValid := MuxCase(
      currentValid,
      Seq(
        out.flush -> false.B,
        in.fire -> true.B,
        out.fire -> false.B
      )
    )
  }
}
