package nzea_core.backend.fu

import chisel3._
import chisel3.util.Decoupled
/** SYSU write-back payload. */
class SysuOut extends Bundle {
  val rd_addr = UInt(5.W)
  val rd_data = UInt(32.W)
}

/** SYSU FU: stub. */
class SYSU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new Bundle {}))
    val out = Decoupled(new SysuOut)
  })
  io.out.valid        := io.in.valid
  io.in.ready         := io.out.ready
  io.out.bits.rd_addr := 0.U
  io.out.bits.rd_data := 0.U
}
