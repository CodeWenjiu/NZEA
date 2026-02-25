package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Valid, Mux1H}
import nzea_core.backend.fu.{AluOut, BruOut, LsuOut, SysuOut}

/** WBU: four FU result inputs; at most one valid per cycle; Mux1H to drive GPR write. Ready always high for now. */
class WBU extends Module {
  val io = IO(new Bundle {
    val alu_in  = Flipped(Decoupled(new AluOut))
    val bru_in  = Flipped(Decoupled(new BruOut))
    val lsu_in  = Flipped(Decoupled(new LsuOut))
    val sysu_in = Flipped(Decoupled(new SysuOut))
    val gpr_wr  = Output(Valid(new Bundle {
      val addr = UInt(5.W)
      val data = UInt(32.W)
    }))
  })

  val valids   = Seq(io.alu_in.valid, io.bru_in.valid, io.lsu_in.valid, io.sysu_in.valid)
  val anyValid = valids.reduce(_ || _)
  val sel      = valids :+ !anyValid

  val rd_addr = Mux1H(sel, Seq(io.alu_in.bits.rd_addr, io.bru_in.bits.rd_addr, io.lsu_in.bits.rd_addr, io.sysu_in.bits.rd_addr, 0.U(5.W)))
  val rd_data = Mux1H(sel, Seq(io.alu_in.bits.rd_data, io.bru_in.bits.rd_data, io.lsu_in.bits.rd_data, io.sysu_in.bits.rd_data, 0.U(32.W)))

  io.gpr_wr.valid := anyValid && (rd_addr =/= 0.U)
  io.gpr_wr.bits.addr := rd_addr
  io.gpr_wr.bits.data := rd_data

  io.alu_in.ready  := true.B
  io.bru_in.ready   := true.B
  io.lsu_in.ready   := true.B
  io.sysu_in.ready  := true.B
}
