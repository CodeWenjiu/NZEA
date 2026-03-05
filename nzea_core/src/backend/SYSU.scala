package nzea_core.backend

import chisel3._
import chisel3.util.Valid
import nzea_core.PipeIO
import nzea_core.retire.rob.Rob

/** SYSU FU input: rob_id, pc from IS. robIdWidth from upper level. */
class SysuInput(robIdWidth: Int) extends Bundle {
  val rob_id = UInt(robIdWidth.W)
  val pc     = UInt(32.W)
}

/** SYSU FU: stub; writes result to Rob via rob_access. */
class SYSU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new SysuInput(robIdWidth)))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
  })
  val next_pc = io.in.bits.pc + 4.U
  val u = Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, is_done = true.B, need_mem = false.B, 0.U(32.W), next_pc = next_pc)(robIdWidth)
  io.rob_access.valid := u.valid
  io.rob_access.bits := u.bits
  io.in.ready := true.B
  io.in.flush := io.rob_access.flush
}
