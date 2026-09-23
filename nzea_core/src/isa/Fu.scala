package nzea_core.isa

import chisel3._

/** Function unit type for routing (ALU/BRU/LSU/MUL/DIV/SYSU). ISA-level: the decode table produces it, the middle
  * dispatches on it, the backend routes by it.
  */
object FuType extends chisel3.ChiselEnum {
  val ALU = Value
  val BRU = Value
  val LSU = Value
  val MUL = Value
  val DIV = Value
  val SYSU = Value
}

/** ALU operand source for opA/opB; one-hot (Rs1Rs2, Rs1Imm, ImmZero, PcImm). */
object AluSrc extends chisel3.ChiselEnum {
  val Rs1Rs2 = Value((1 << 0).U)
  val Rs1Imm = Value((1 << 1).U)
  val ImmZero = Value((1 << 2).U)
  val PcImm = Value((1 << 3).U)
}

/** BRU target source: one-hot (PcImm, Rs1Imm). */
object BruSrc extends chisel3.ChiselEnum {
  val PcImm = Value((1 << 0).U)
  val Rs1Imm = Value((1 << 1).U)
}

/** LSU address source: one-hot (Rs1Imm). */
object LsuSrc extends chisel3.ChiselEnum {
  val Rs1Imm = Value((1 << 0).U)
}

/** Single FU descriptor per instruction: op + src for that FU (no cross-FU op/src). Decode yields fu_type, fu_op,
  * fu_src.
  */
object Fu {
  sealed trait Type
  case class ALU(op: AluOp.Type, src: AluSrc.Type) extends Type
  case class BRU(op: BruOp.Type, src: BruSrc.Type) extends Type
  case class LSU(op: LsuOp.Type, src: LsuSrc.Type) extends Type
  case class MUL(op: MulOp.Type) extends Type
  case class DIV(op: DivOp.Type) extends Type
  case class SYSU(op: SysuOp.Type) extends Type

  def fuTypeOf(fu: Fu.Type): FuType.Type = fu match {
    case ALU(_, _) => FuType.ALU
    case BRU(_, _) => FuType.BRU
    case LSU(_, _) => FuType.LSU
    case MUL(_)    => FuType.MUL
    case DIV(_)    => FuType.DIV
    case SYSU(_)   => FuType.SYSU
  }

  def opLitValue(fu: Fu.Type): BigInt = fu match {
    case ALU(op, _) => op.litValue
    case BRU(op, _) => op.litValue
    case LSU(op, _) => op.litValue
    case MUL(op)    => op.litValue
    case DIV(op)    => op.litValue
    case SYSU(op)   => op.litValue
  }

  def srcLitValue(fu: Fu.Type): BigInt = fu match {
    case ALU(_, s) => s.litValue
    case BRU(_, s) => s.litValue
    case LSU(_, s) => s.litValue
    case MUL(_)    => BigInt(0)
    case DIV(_)    => BigInt(0)
    case SYSU(_)   => BigInt(0)
  }

}

/** fu_src width: max of all FU src enum widths; when adding/changing FUs, update this Seq and the ChiselEnums only. */
object FuSrcWidth {
  val Width: Int = Seq(AluSrc.getWidth, BruSrc.getWidth, LsuSrc.getWidth).max
}

/** Slice unified fu_src/fu_op by enum width for each FU's .safe(); use take(fu_*, X.getWidth) at every consumer, no
  * manual bit-width.
  */
object FuDecode {
  def take(u: UInt, width: Int): UInt = u(width - 1, 0)
}