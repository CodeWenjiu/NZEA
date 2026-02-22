package nzea_core

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, MuxCase, RegEnable}

class PipelineCtrl extends Bundle {
  val stall = Output(Bool())
  val flush = Output(Bool())
}

object PipelineReg {
  /** Pipeline reg without stall/flush: builds ctrl with stall=false, flush=false and calls the full apply. */
  def apply[T <: Data](in: DecoupledIO[T]): DecoupledIO[T] = {
    val ctrl = Wire(new PipelineCtrl)
    ctrl.stall := false.B
    ctrl.flush := false.B
    apply(in, ctrl)
  }

  def apply[T <: Data](in: DecoupledIO[T], ctrl: PipelineCtrl): DecoupledIO[T] = {
    val out = Wire(Decoupled(chiselTypeOf(in.bits)))

    val currentValid = Wire(Bool())

    in.ready  := (!currentValid || out.ready) && !ctrl.stall
    out.valid := currentValid && !ctrl.stall

    out.bits := RegEnable(in.bits, in.fire)
    currentValid := RegNext(
      next = MuxCase(
        currentValid,
        Seq(
          ctrl.flush -> false.B,
          in.fire    -> true.B, 
          out.fire   -> false.B 
        )
      ),
      init = false.B
    )

    out
  }
}
