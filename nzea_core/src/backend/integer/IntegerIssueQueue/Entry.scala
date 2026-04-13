package nzea_core.backend.integer

import chisel3._
import nzea_core.frontend.{FuSrcWidth, FuType}
import nzea_core.retire.rob.RobMemType

/** Integer issue queue entry: FuType + operand tags (paddr) + ready.
  * No source data; values are read via PRF/bypass at dispatch.
  */
class IntegerIssueQueueEntry(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int) extends Bundle {
  val fu_type        = FuType()
  val rs1_ready      = Bool()
  val rs2_ready      = Bool()
  val p_rs1          = UInt(prfAddrWidth.W)
  val p_rs2          = UInt(prfAddrWidth.W)
  val p_rd           = UInt(prfAddrWidth.W)
  val old_p_rd       = UInt(prfAddrWidth.W)
  val rd_index       = UInt(5.W)
  val imm            = UInt(32.W)
  val pc             = UInt(32.W)
  val pred_next_pc   = UInt(32.W)
  val fu_op          = UInt(FuOpWidth.Width.W)
  val fu_src         = UInt(FuSrcWidth.Width.W)
  val csr_addr       = UInt(12.W)
  /** Decode-time: SYSU will write CSR (rs1/zimm non-zero for CSRRS/CSRRC/… ). */
  val csr_will_write = Bool()
  val rob_id         = UInt(robIdWidth.W)
  val lsq_id         = UInt(lsqIdWidth.W)
  val mem_type       = RobMemType()
}
