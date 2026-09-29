package nzea_core.front.idu.inst

import nzea_core.back.int.{DivOp, MulOp}
import nzea_core.isa.Xlen
import Dispatch.*

/** The M extension's rows — `funct7`=0000001 — and the order they land in the table: the base block, then the word
  * forms a 64-bit configuration adds.
  *
  * A row says nothing about whether it is issuable: the word members are gated where the ops are ([[MulOp.exists]] and
  * [[DivOp.exists]] answer for them), so the dispatch carries that question.
  */
private[idu] object InstM:

  val MUL = RVInst("MUL", rType("0000001", "000", "0110011"), None, mul(MulOp.Mul))
  val MULH = RVInst("MULH", rType("0000001", "001", "0110011"), None, mul(MulOp.Mulh))
  val MULHSU = RVInst("MULHSU", rType("0000001", "010", "0110011"), None, mul(MulOp.Mulhsu))
  val MULHU = RVInst("MULHU", rType("0000001", "011", "0110011"), None, mul(MulOp.Mulhu))
  val DIV = RVInst("DIV", rType("0000001", "100", "0110011"), None, div(DivOp.Div))
  val DIVU = RVInst("DIVU", rType("0000001", "101", "0110011"), None, div(DivOp.Divu))
  val REM = RVInst("REM", rType("0000001", "110", "0110011"), None, div(DivOp.Rem))
  val REMU = RVInst("REMU", rType("0000001", "111", "0110011"), None, div(DivOp.Remu))

  val MULW = RVInst("MULW", rType("0000001", "000", "0111011"), None, mul(MulOp.Mulw))
  val DIVW = RVInst("DIVW", rType("0000001", "100", "0111011"), None, div(DivOp.Divw))
  val DIVUW = RVInst("DIVUW", rType("0000001", "101", "0111011"), None, div(DivOp.Divuw))
  val REMW = RVInst("REMW", rType("0000001", "110", "0111011"), None, div(DivOp.Remw))
  val REMUW = RVInst("REMUW", rType("0000001", "111", "0111011"), None, div(DivOp.Remuw))

  /** The table order for one register width: the base block, then the word forms when the registers are 64 bits wide.
    */
  def rows(xlen: Xlen): Seq[RVInst] =
    val word =
      if xlen == Xlen.Rv64 then Seq(MULW, DIVW, DIVUW, REMW, REMUW)
      else Seq.empty
    Seq(MUL, MULH, MULHSU, MULHU, DIV, DIVU, REM, REMU) ++ word
