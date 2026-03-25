package nzea_core.backend.integer

/** Unified fu_op width: max of all FU opcode widths; used by decode/IDU/ISU and integer issue queue. */
object FuOpWidth {
  val Width: Int = Seq(AluOp.getWidth, BruOp.getWidth, LsuOp.getWidth, MulOp.getWidth, DivOp.getWidth, SysuOp.getWidth).max
}
