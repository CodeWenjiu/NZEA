package nzea_core.backend.fu

import chisel3._
import chisel3.util.Decoupled
/** SYSU write-back payload (rd_index from Rob head). */
class SysuOut extends Bundle {
  val rd_data = UInt(32.W)
}

/** SYSU FU input: empty (next_pc from ROB). */
class SysuInput extends Bundle {}

/** SYSU FU: stub. */
class SYSU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new SysuInput))
    val out = Decoupled(new SysuOut)
  })
  io.out.valid        := io.in.valid
  io.out.bits.rd_data := 0.U
  io.in.ready         := io.out.ready
}
