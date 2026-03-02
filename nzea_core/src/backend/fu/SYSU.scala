package nzea_core.backend.fu

import chisel3._
import chisel3.util.Valid
import nzea_core.PipeIO
import nzea_core.backend.{Rob, RobState}

/** SYSU FU input: rob_id from IS (next_pc from ROB head). robIdWidth from upper level. */
class SysuInput(robIdWidth: Int) extends Bundle {
  val rob_id = UInt(robIdWidth.W)
}

/** SYSU FU: stub; writes result to Rob via rob_access. */
class SYSU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new SysuInput(robIdWidth)))
    val rob_access = new nzea_core.backend.RobAccessIO(robIdWidth)
  })
  val u = Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, RobState.Done, 0.U(32.W))(robIdWidth)
  io.rob_access.valid := u.valid
  io.rob_access.bits := u.bits
  io.in.ready := true.B
  io.in.flush := io.rob_access.flush
}
