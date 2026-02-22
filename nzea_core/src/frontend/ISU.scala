package nzea_core.frontend

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.Mux1H

/** ISU (Instruction Schedule Unit): dispatches decoded instructions to FU-specific queues.
  * Four Decoupled outputs for ALU, BRU, LSU, SYSU; payload empty for now.
  */
class ISU(addrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new IDUOut(addrWidth)))
    val alu  = Decoupled(new Bundle {})
    val bru  = Decoupled(new Bundle {})
    val lsu  = Decoupled(new Bundle {})
    val sysu = Decoupled(new Bundle {})
  })

  val fu = io.in.bits.fu_type
  val outs = Seq(io.alu, io.bru, io.lsu, io.sysu)
  val fuTypes = Seq(FuType.ALU, FuType.BRU, FuType.LSU, FuType.SYSU)

  outs.zip(fuTypes).foreach { case (out, t) =>
    out.valid := io.in.valid && (fu === t)
    out.bits := DontCare
  }
  io.in.ready := Mux1H(fuTypes.map(_ === fu), outs.map(_.ready))
}
