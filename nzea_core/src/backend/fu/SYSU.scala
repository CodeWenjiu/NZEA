package nzea_core.backend.fu

import chisel3._
import nzea_core.PipeIO
/** SYSU write-back payload (rd_index from Rob head). */
class SysuOut extends Bundle {
  val rd_data = UInt(32.W)
}

/** SYSU FU input: rob_id from IS (next_pc from ROB head). robIdWidth from upper level. */
class SysuInput(robIdWidth: Int) extends Bundle {
  val rob_id = UInt(robIdWidth.W)
}

/** SYSU FU: stub. robIdWidth from upper level. */
class SYSU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new SysuInput(robIdWidth)))
    val out = new PipeIO(new SysuOut)
  })
  io.out.valid        := io.in.valid
  io.out.bits.rd_data := 0.U
  io.in.ready         := io.out.ready
  io.in.flush         := io.out.flush
}
