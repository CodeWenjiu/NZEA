package nzea_core.retire

import chisel3._
import chisel3.util.Valid
import nzea_core.frontend.CsrType
import nzea_config.NzeaConfig

/** Internal Rob→Commit payload: all fields for bookkeeping. */
class RobCommitPayload(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id    = UInt(robIdWidth.W)
  val rd_index  = UInt(5.W)
  val p_rd      = UInt(prfAddrWidth.W)
  val old_p_rd  = UInt(prfAddrWidth.W)
  val next_pc   = UInt(32.W)
  val mem_count = UInt(32.W)
  val is_load   = Bool()
  val csr_type  = CsrType()
  val csr_data  = UInt(32.W)
}

/** Commit logical state (external): only architectural state changes. */
class CommitMsg extends Bundle {
  val next_pc   = UInt(32.W)
  val rd_index  = UInt(5.W)
  val rd_value  = UInt(32.W)
  val mem_count = UInt(32.W)
  val is_load   = Bool()
  val csr_type  = CsrType()
  val csr_data  = UInt(32.W)
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
  private val robIdWidth   = chisel3.util.log2Ceil(config.robDepth.max(2))

  val io = IO(new Bundle {
    val rob_commit     = Flipped(Valid(new RobCommitPayload(robIdWidth, prfAddrWidth)))
    val prf_read       = new nzea_core.frontend.PrfReadIO(prfAddrWidth)
    val do_flush       = Input(Bool())
    val commit_msg     = Output(Valid(new CommitMsg))
    val redirect_pc    = Output(UInt(32.W))
    val idu_commit     = Output(Valid(new IDUCommit(prfAddrWidth)))
    val restore_rmt    = Output(Vec(31, UInt(prfAddrWidth.W)))
    val commit_rob_id  = Output(UInt(robIdWidth.W))
    val commit_valid   = Output(Bool())
  })

  val c = io.rob_commit.bits
  val any_commit = io.rob_commit.valid

  io.commit_msg.valid := any_commit
  io.commit_msg.bits.next_pc   := c.next_pc
  io.commit_msg.bits.rd_index  := c.rd_index
  io.commit_msg.bits.rd_value  := io.prf_read.data
  io.commit_msg.bits.mem_count := c.mem_count
  io.commit_msg.bits.is_load   := c.is_load
  io.commit_msg.bits.csr_type  := c.csr_type
  io.commit_msg.bits.csr_data  := c.csr_data
  io.redirect_pc := RegNext(c.next_pc)

  io.idu_commit.valid := any_commit
  io.idu_commit.bits.rd_index := c.rd_index
  io.idu_commit.bits.p_rd := c.p_rd
  io.idu_commit.bits.old_p_rd := c.old_p_rd

  io.prf_read.addr := c.p_rd
  io.commit_rob_id := c.rob_id
  io.commit_valid  := any_commit

  // AMT: 31 entries for AR1~AR31, updated on commit.
  // restore_rmt = amt (registered output only), breaks rob->commit combinational path.
  val amt = RegInit(VecInit(Seq.tabulate(31)(i => (i + 1).U(prfAddrWidth.W))))
  when(any_commit && c.rd_index =/= 0.U) {
    amt(c.rd_index - 1.U) := c.p_rd
  }
  io.restore_rmt := amt
}
