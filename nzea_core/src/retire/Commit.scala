package nzea_core.retire

import chisel3._
import chisel3.util.Valid
import nzea_config.NzeaConfig

/** Commit payload: next_pc, rd_index, rd_value, p_rd, old_p_rd, mem_count, is_load. */
class CommitMsg(prfAddrWidth: Int) extends Bundle {
  val next_pc   = UInt(32.W)
  val rd_index  = UInt(5.W)
  val rd_value  = UInt(32.W)
  val p_rd      = UInt(prfAddrWidth.W)
  val old_p_rd  = UInt(prfAddrWidth.W)
  val mem_count = UInt(32.W)
  val is_load   = Bool()
}

/** IDU commit input: rd_index, p_rd, old_p_rd. */
class IDUCommit(prfAddrWidth: Int) extends Bundle {
  val rd_index = UInt(5.W)
  val p_rd     = UInt(prfAddrWidth.W)
  val old_p_rd = UInt(prfAddrWidth.W)
}

/** Commit: receives Rob commit; maintains AMT; outputs commit to IDU.
  * AMT: updated on commit (rd -> p_rd). On flush, restore_rmt=AMT, restore_free from AMT.
  */
class Commit(implicit config: NzeaConfig) extends Module {
  private val prfAddrWidth = config.prfAddrWidth

  val io = IO(new Bundle {
    val rob_commit   = Flipped(Valid(new CommitMsg(prfAddrWidth)))
    val do_flush     = Input(Bool())
    val commit_msg   = Output(Valid(new CommitMsg(prfAddrWidth)))
    val redirect_pc  = Output(UInt(32.W))
    val idu_commit   = Output(Valid(new IDUCommit(prfAddrWidth)))
    val restore_rmt  = Output(Vec(31, UInt(prfAddrWidth.W)))
  })

  val c = io.rob_commit.bits
  val any_commit = io.rob_commit.valid

  io.commit_msg.valid := any_commit
  io.commit_msg.bits := c
  io.redirect_pc := c.next_pc

  io.idu_commit.valid := any_commit
  io.idu_commit.bits.rd_index := c.rd_index
  io.idu_commit.bits.p_rd := c.p_rd
  io.idu_commit.bits.old_p_rd := c.old_p_rd

  // AMT: 31 entries for AR1~AR31, updated on commit
  val amt = RegInit(VecInit(Seq.tabulate(31)(i => (i + 1).U(prfAddrWidth.W))))
  when(any_commit && c.rd_index =/= 0.U) {
    amt(c.rd_index - 1.U) := c.p_rd
  }
  for (i <- 0 until 31) { io.restore_rmt(i) := amt(i) }
  when(any_commit && c.rd_index =/= 0.U) {
    io.restore_rmt(c.rd_index - 1.U) := c.p_rd
  }
}
