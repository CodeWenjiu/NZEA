package nzea_core.backend.fu

import chisel3._
import chisel3.util.Decoupled
import nzea_core.backend.ExuOut

/** BRU FU: stub. */
class BRU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new Bundle {}))
    val out = Decoupled(new ExuOut)
  })
  io.out.valid := io.in.valid
  io.in.ready   := io.out.ready
  io.out.bits.rd_wen  := false.B
  io.out.bits.rd_addr := 0.U
  io.out.bits.rd_data := 0.U
}
