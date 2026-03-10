package nzea_core.retire

import chisel3._
import chisel3.util.{Valid, Mux1H}
import nzea_config.NzeaConfig

/** Commit payload: next_pc, rd_index, rd_value, p_rd (for RMT checkpoint), old_p_rd (for FreeList), mem_count, is_load. */
class CommitMsg(prfAddrWidth: Int) extends Bundle {
  val next_pc   = UInt(32.W)
  val rd_index  = UInt(5.W)
  val rd_value  = UInt(32.W)
  val p_rd      = UInt(prfAddrWidth.W)  // for RMT checkpoint: rd -> p_rd
  val old_p_rd  = UInt(prfAddrWidth.W)   // for FreeList push
  val mem_count = UInt(32.W)  // 0 or 1, from need_mem, for diff/trace
  val is_load   = Bool()      // true if mem op is load, false if store (don't care when mem_count=0)
}

/** Commit: receives Rob commit via Valid (only when head is Done); commits and writes GPR. */
class Commit(implicit config: NzeaConfig) extends Module {
  private val robDepth     = config.robDepth
  private val prfAddrWidth = config.prfAddrWidth

  val io = IO(new Bundle {
    val rob_commit  = Flipped(Valid(new CommitMsg(prfAddrWidth)))
    val commit_msg  = Output(Valid(new CommitMsg(prfAddrWidth)))
    val redirect_pc = Output(UInt(32.W))
  })

  val c = io.rob_commit.bits
  val any_commit = io.rob_commit.valid

  io.commit_msg.valid := any_commit
  io.commit_msg.bits := c

  io.redirect_pc := c.next_pc
}
