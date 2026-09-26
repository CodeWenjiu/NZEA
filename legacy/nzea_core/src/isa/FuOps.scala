package nzea_core.isa

import chisel3._

/** Per-FU operation enums and the unified fu_op width. ISA-level: the decode table encodes them, each FU decodes its
  * own slice with `FuDecode.take(fu_op, X.getWidth)`.
  */

/** ALU op: one-hot for Mux1H (add, sub, and, or, xor, sll, srl, sra, slt, sltu). */
object AluOp extends chisel3.ChiselEnum {
  val Add = Value((1 << 0).U)
  val Sub = Value((1 << 1).U)
  val And = Value((1 << 2).U)
  val Or = Value((1 << 3).U)
  val Xor = Value((1 << 4).U)
  val Sll = Value((1 << 5).U)
  val Srl = Value((1 << 6).U)
  val Sra = Value((1 << 7).U)
  val Slt = Value((1 << 8).U)
  val Sltu = Value((1 << 9).U)
}

/** BRU op: one-hot (JAL, JALR, BEQ, BNE, BLT, BGE, BLTU, BGEU). */
object BruOp extends chisel3.ChiselEnum {
  val JAL = Value((1 << 0).U)
  val JALR = Value((1 << 1).U)
  val BEQ = Value((1 << 2).U)
  val BNE = Value((1 << 3).U)
  val BLT = Value((1 << 4).U)
  val BGE = Value((1 << 5).U)
  val BLTU = Value((1 << 6).U)
  val BGEU = Value((1 << 7).U)
}

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

/** MUL FU op: MUL, MULH, MULHSU, MULHU (M extension). */
object MulOp extends chisel3.ChiselEnum {
  val Mul = Value((1 << 0).U)
  val Mulh = Value((1 << 1).U)
  val Mulhsu = Value((1 << 2).U)
  val Mulhu = Value((1 << 3).U)
}

/** DIV FU op: DIV, DIVU, REM, REMU (M extension). */
object DivOp extends chisel3.ChiselEnum {
  val Div = Value((1 << 0).U)
  val Divu = Value((1 << 1).U)
  val Rem = Value((1 << 2).U)
  val Remu = Value((1 << 3).U)
}

/** SYSU op: CSR ops + ECALL/EBREAK/FENCE. Decoded from funct3 like other FU ops. */
object SysuOp extends chisel3.ChiselEnum {
  val CSRRW = Value // 001
  val CSRRS = Value // 010
  val CSRRC = Value // 011
  val CSRRWI = Value // 101
  val CSRRSI = Value // 110
  val CSRRCI = Value // 111
  val ECALL = Value
  val EBREAK = Value
  val FENCE = Value
}

/** Unified fu_op width: max of all FU opcode widths; used by decode and the issue uop. */
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