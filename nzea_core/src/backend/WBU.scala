package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Valid}

/** WBU (Write-Back Unit): receives EXU result, drives GPR write. */
class WBU extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new ExuOut))
    val gpr_wr = Output(Valid(new Bundle {
      val addr = UInt(5.W)
      val data = UInt(32.W)
    }))
  })
  io.gpr_wr.valid := io.in.fire && io.in.bits.rd_wen
  io.gpr_wr.bits.addr := io.in.bits.rd_addr
  io.gpr_wr.bits.data := io.in.bits.rd_data
  io.in.ready := true.B
}
