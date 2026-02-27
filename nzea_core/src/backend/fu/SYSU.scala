package nzea_core.backend.fu

import chisel3._
import chisel3.util.Decoupled
/** SYSU write-back payload (rd_index from Rob head). */
class SysuOut extends Bundle {
  val rd_data = UInt(32.W)
  val next_pc = UInt(32.W)
}

/** SYSU FU input: pc for next_pc. */
class SysuInput extends Bundle {
  val pc = UInt(32.W)
}

/** SYSU FU: stub. */
class SYSU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new SysuInput))
    val out = Decoupled(new SysuOut)
  })
  io.out.valid        := io.in.valid
  io.out.bits.rd_data := 0.U
  io.out.bits.next_pc := io.in.bits.pc + 4.U
  io.in.ready         := io.out.ready
}
