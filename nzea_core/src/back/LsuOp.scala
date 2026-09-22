package nzea_core.back

import chisel3._

/** LsuOp: one-hot (LB, LH, LW, LBU, LHU, SB, SH, SW). Used by decode, the LSU and the bus user payload. */
object LsuOp extends chisel3.ChiselEnum {
  val LB = Value((1 << 0).U)
  val LH = Value((1 << 1).U)
  val LW = Value((1 << 2).U)
  val LBU = Value((1 << 3).U)
  val LHU = Value((1 << 4).U)
  val SB = Value((1 << 5).U)
  val SH = Value((1 << 6).U)
  val SW = Value((1 << 7).U)

  def isStore(op: LsuOp.Type): Bool = op === SB || op === SH || op === SW
  def isLoad(op: LsuOp.Type): Bool = !isStore(op)
  def isStore(op: UInt): Bool = op === SB.asUInt || op === SH.asUInt || op === SW.asUInt
  def isLoad(op: UInt): Bool = !isStore(op)
}