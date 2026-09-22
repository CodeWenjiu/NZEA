package nzea_core.back

/** Unified fu_op width: max of all FU opcode widths; used by decode/IDU and the issue uop. */
object FuOpWidth {

  val Width: Int =
    Seq(
      AluOp.getWidth,
      BruOp.getWidth,
      LsuOp.getWidth,
      MulOp.getWidth,
      DivOp.getWidth,
      SysuOp.getWidth
    ).max

}