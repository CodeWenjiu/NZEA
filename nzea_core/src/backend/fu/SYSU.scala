package nzea_core.backend.fu

import chisel3._
import chisel3.util.Valid
import nzea_core.PipeIO
import nzea_core.backend.{Rob, RobState}
/** SYSU write-back payload: rd_data, rob_id, rob_entry_access (from Rob.entryStateUpdate). */
class SysuOut(robIdWidth: Int) extends Bundle {
  val rd_data          = UInt(32.W)
  val rob_id           = UInt(robIdWidth.W)
  val rob_entry_access = Valid(new nzea_core.backend.RobEntryStateUpdate(robIdWidth))
}

/** SYSU FU input: rob_id from IS (next_pc from ROB head). robIdWidth from upper level. */
class SysuInput(robIdWidth: Int) extends Bundle {
  val rob_id = UInt(robIdWidth.W)
}

/** SYSU FU: stub. robIdWidth from upper level. */
class SYSU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new SysuInput(robIdWidth)))
    val out = new PipeIO(new SysuOut(robIdWidth))
  })
  io.out.valid := io.in.valid
  io.out.bits.rd_data := 0.U
  io.out.bits.rob_id := io.in.bits.rob_id
  io.out.bits.rob_entry_access := Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, RobState.Done)(robIdWidth)
  io.in.ready := io.out.ready
  io.in.flush := io.out.flush
}
