package nzea_core.backend.vector

import chisel3._

/** PRF-style write to physical vector registers (single 32-bit lane in scaffold). */
class VrfWriteBundle(pvrAddrWidth: Int) extends Bundle {
  val addr = UInt(pvrAddrWidth.W)
  val data = UInt(32.W)
}

/** VIQ entry after rename (future: driven from IDU/ISU). */
class VectorIssueQueueEntry(robIdWidth: Int, pvrAddrWidth: Int) extends Bundle {
  val rob_id = UInt(robIdWidth.W)
  /** Encoded [[ValuOp]] */
  val valu_op = UInt(ValuOp.getWidth.W)
  val p_vs1   = UInt(pvrAddrWidth.W)
  val p_vs2   = UInt(pvrAddrWidth.W)
  val p_vd    = UInt(pvrAddrWidth.W)
  val imm     = UInt(32.W)
}

/** Payload from VIQ read stage to [[VALU]]. */
class ValuInput(robIdWidth: Int, pvrAddrWidth: Int) extends Bundle {
  val valu_op = ValuOp()
  val vs1     = UInt(32.W)
  val vs2     = UInt(32.W)
  val imm     = UInt(32.W)
  val p_vd    = UInt(pvrAddrWidth.W)
  val rob_id  = UInt(robIdWidth.W)
}
