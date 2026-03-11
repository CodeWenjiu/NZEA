package nzea_core.backend

import chisel3._
import chisel3.util.Valid
import nzea_core.PipeIO
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

/** SYSU FU input: rob_id, pc, p_rd from IS. */
class SysuInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id = UInt(robIdWidth.W)
  val pc     = UInt(32.W)
  val p_rd   = UInt(prfAddrWidth.W)
}

/** SYSU FU: stub; writes result to Rob (commit) and PRF (direct). */
class SYSU(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new SysuInput(robIdWidth, prfAddrWidth)))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
    val prf_write  = Output(Valid(new PrfWriteBundle(prfAddrWidth)))
  })
  val next_pc = io.in.bits.pc + 4.U
  val u = Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, is_done = true.B, need_mem = false.B, next_pc = next_pc)(robIdWidth)
  io.rob_access.valid := u.valid
  io.rob_access.bits := u.bits
  io.in.ready := true.B
  io.in.flush := io.rob_access.flush

  io.prf_write.valid := u.valid && io.in.bits.p_rd =/= 0.U
  io.prf_write.bits.addr := io.in.bits.p_rd
  io.prf_write.bits.data := 0.U(32.W)
}
