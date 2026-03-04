package nzea_core.retire

import chisel3._
import chisel3.util.{Decoupled, Mux1H, Valid}
import nzea_config.NzeaConfig
import nzea_core.retire.rob.RobCommitInfo

/** Commit message for Debugger/Difftest: next_pc (real PC after commit) and GPR
  * write.
  */
class CommitMsg extends Bundle {
  val valid = Bool()
  val next_pc = UInt(32.W)
  val gpr_addr = UInt(5.W)
  val gpr_data = UInt(32.W)
}

/** WBU: receives Rob commit via Decoupled (only when head is Done); commits and writes GPR. */
class WBU(implicit config: NzeaConfig) extends Module {
  private val robDepth = config.robDepth

  val io = IO(new Bundle {
    val rob_commit  = Flipped(Decoupled(new RobCommitInfo))
    val gpr_wr = Output(new Bundle {
      val addr = UInt(5.W)
      val data = UInt(32.W)
    })
    val commit_msg  = Output(new CommitMsg)
    val redirect_pc = Output(UInt(32.W))
  })

  val c = io.rob_commit.bits
  val can_commit = io.rob_commit.valid

  io.rob_commit.ready := true.B

  val any_commit = can_commit
  io.gpr_wr.addr := Mux(any_commit, c.rd_index, 0.U)
  io.gpr_wr.data := c.rd_value

  io.commit_msg.valid := any_commit
  io.commit_msg.next_pc := c.next_pc
  io.commit_msg.gpr_addr := c.rd_index
  io.commit_msg.gpr_data := c.rd_value

  io.redirect_pc := c.next_pc
}
