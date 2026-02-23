package nzea_core.frontend

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.Mux1H
import nzea_core.backend.fu.AluInput

/** ISU (Instruction Schedule Unit): dispatches decoded instructions to FU-specific queues. */
class ISU(addrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new IDUOut(addrWidth)))
    val alu  = Decoupled(new AluInput)
    val bru  = Decoupled(new Bundle {})
    val lsu  = Decoupled(new Bundle {})
    val sysu = Decoupled(new Bundle {})
  })

  val fu = io.in.bits.fu_type
  val outs = Seq(io.alu, io.bru, io.lsu, io.sysu)
  val fuTypes = Seq(FuType.ALU, FuType.BRU, FuType.LSU, FuType.SYSU)

  io.alu.valid := io.in.valid && (fu === FuType.ALU)
  io.alu.bits.opA      := io.in.bits.rs1
  io.alu.bits.opB      := io.in.bits.rs2
  io.alu.bits.aluOp    := io.in.bits.aluOp
  io.alu.bits.rd_index := io.in.bits.rd_index

  Seq((io.bru, FuType.BRU), (io.lsu, FuType.LSU), (io.sysu, FuType.SYSU)).foreach { case (out, t) =>
    out.valid := io.in.valid && (fu === t)
    out.bits := DontCare
  }
  io.in.ready := Mux1H(fuTypes.map(_ === fu), outs.map(_.ready))
}
