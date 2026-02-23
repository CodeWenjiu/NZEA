package nzea_core.frontend

import chisel3._
import chisel3.util.BitPat
import chisel3.util.experimental.decode.{DecodeField, DecodePattern, TruthTable, QMCMinimizer, decoder}
import nzea_core.backend.fu.AluOp

// -------- Instruction pattern & decode fields (ImmType, FuType, RVInst, RiscvInsts) --------

/** One-hot encoding for Mux1H; better timing than binary. */
object ImmType extends chisel3.ChiselEnum {
  val I = Value((1 << 0).U)
  val S = Value((1 << 1).U)
  val B = Value((1 << 2).U)
  val U = Value((1 << 3).U)
  val J = Value((1 << 4).U)
}

/** Function unit type: which FU executes this instruction (binary encoding). */
object FuType extends chisel3.ChiselEnum {
  val ALU  = Value  // basic integer
  val BRU  = Value  // branch / jump
  val LSU  = Value  // load / store
  val SYSU = Value  // CSR / system
}

/** RISC-V instruction pattern + optional ALU decode (aluOp). */
case class RVInst(
  name: String,
  bitPatStr: String,
  immType: Option[ImmType.Type],
  fuType: FuType.Type,
  aluOp: Option[AluOp.Type] = None
) extends DecodePattern {
  def bitPat: BitPat = BitPat(bitPatStr)
}

/** RV32I instruction set. Bit layout: [31:25] funct7, [24:20] rs2, [19:15] rs1, [14:12] funct3, [11:7] rd, [6:0] opcode. */
object RiscvInsts {
  def n(n: Int) = "?" * n

  // U-type: LUI=0110111, AUIPC=0010111
  val LUI   = RVInst("LUI",   "b" + n(25) + "0110111", Some(ImmType.U), FuType.ALU, Some(AluOp.Add))
  val AUIPC = RVInst("AUIPC", "b" + n(25) + "0010111", Some(ImmType.U), FuType.ALU, Some(AluOp.Add))

  // J-type: JAL=1101111
  val JAL   = RVInst("JAL",   "b" + n(25) + "1101111", Some(ImmType.J), FuType.BRU)

  // I-type (JALR=1100111, load=0000011, op-imm=0010011, system=1110011)
  val JALR  = RVInst("JALR",  "b" + n(12) + n(5) + "000" + n(5) + "1100111", Some(ImmType.I), FuType.BRU)
  val LB    = RVInst("LB",    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011", Some(ImmType.I), FuType.LSU)
  val LH    = RVInst("LH",    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011", Some(ImmType.I), FuType.LSU)
  val LW    = RVInst("LW",    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011", Some(ImmType.I), FuType.LSU)
  val LBU   = RVInst("LBU",   "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011", Some(ImmType.I), FuType.LSU)
  val LHU   = RVInst("LHU",   "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011", Some(ImmType.I), FuType.LSU)
  val ADDI  = RVInst("ADDI",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.Add))
  val SLTI  = RVInst("SLTI",  "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.Add))
  val SLTIU = RVInst("SLTIU", "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.Add))
  val XORI  = RVInst("XORI",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.Xor))
  val ORI   = RVInst("ORI",   "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.Or))
  val ANDI  = RVInst("ANDI",  "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.And))
  val SLLI  = RVInst("SLLI",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.Sll))
  val SRLI  = RVInst("SRLI",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.Srl))
  val SRAI  = RVInst("SRAI",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011", Some(ImmType.I), FuType.ALU, Some(AluOp.Sra))
  val ECALL = RVInst("ECALL", "b00000000000000000000000001110011", Some(ImmType.I), FuType.SYSU)
  val EBREAK= RVInst("EBREAK","b00000000000100000000000001110011", Some(ImmType.I), FuType.SYSU)
  val CSRRW  = RVInst("CSRRW",  "b" + n(12) + n(5) + "001" + n(5) + "1110011", Some(ImmType.I), FuType.SYSU)
  val CSRRS  = RVInst("CSRRS",  "b" + n(12) + n(5) + "010" + n(5) + "1110011", Some(ImmType.I), FuType.SYSU)
  val CSRRC  = RVInst("CSRRC",  "b" + n(12) + n(5) + "011" + n(5) + "1110011", Some(ImmType.I), FuType.SYSU)
  val CSRRWI = RVInst("CSRRWI", "b" + n(12) + n(5) + "101" + n(5) + "1110011", Some(ImmType.I), FuType.SYSU)
  val CSRRSI = RVInst("CSRRSI", "b" + n(12) + n(5) + "110" + n(5) + "1110011", Some(ImmType.I), FuType.SYSU)
  val CSRRCI = RVInst("CSRRCI", "b" + n(12) + n(5) + "111" + n(5) + "1110011", Some(ImmType.I), FuType.SYSU)

  // S-type: store=0100011
  val SB = RVInst("SB", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0100011", Some(ImmType.S), FuType.LSU)
  val SH = RVInst("SH", "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0100011", Some(ImmType.S), FuType.LSU)
  val SW = RVInst("SW", "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0100011", Some(ImmType.S), FuType.LSU)

  // B-type: branch=1100011
  val BEQ  = RVInst("BEQ",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "1100011", Some(ImmType.B), FuType.BRU)
  val BNE  = RVInst("BNE",  "b" + n(7) + n(5) + n(5) + "001" + n(5) + "1100011", Some(ImmType.B), FuType.BRU)
  val BLT  = RVInst("BLT",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "1100011", Some(ImmType.B), FuType.BRU)
  val BGE  = RVInst("BGE",  "b" + n(7) + n(5) + n(5) + "101" + n(5) + "1100011", Some(ImmType.B), FuType.BRU)
  val BLTU = RVInst("BLTU", "b" + n(7) + n(5) + n(5) + "110" + n(5) + "1100011", Some(ImmType.B), FuType.BRU)
  val BGEU = RVInst("BGEU", "b" + n(7) + n(5) + n(5) + "111" + n(5) + "1100011", Some(ImmType.B), FuType.BRU)

  // R-type: no imm, dontCare for QMC
  val ADD  = RVInst("ADD",  "b0000000" + n(5) + n(5) + "000" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Add))
  val SUB  = RVInst("SUB",  "b0100000" + n(5) + n(5) + "000" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Sub))
  val SLL  = RVInst("SLL",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Sll))
  val SLT  = RVInst("SLT",  "b0000000" + n(5) + n(5) + "010" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Sub))
  val SLTU = RVInst("SLTU", "b0000000" + n(5) + n(5) + "011" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Sub))
  val XOR  = RVInst("XOR",  "b0000000" + n(5) + n(5) + "100" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Xor))
  val SRL  = RVInst("SRL",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Srl))
  val SRA  = RVInst("SRA",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Sra))
  val OR   = RVInst("OR",   "b0000000" + n(5) + n(5) + "110" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.Or))
  val AND  = RVInst("AND",  "b0000000" + n(5) + n(5) + "111" + n(5) + "0110011", None, FuType.ALU, Some(AluOp.And))

  val FENCE = RVInst("FENCE", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0001111", None, FuType.SYSU)

  val all: Seq[RVInst] = Seq(
    LUI, AUIPC, JAL, JALR,
    BEQ, BNE, BLT, BGE, BLTU, BGEU,
    LB, LH, LW, LBU, LHU, SB, SH, SW,
    ADDI, SLTI, SLTIU, XORI, ORI, ANDI, SLLI, SRLI, SRAI,
    ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND,
    FENCE, ECALL, EBREAK,
    CSRRW, CSRRS, CSRRC, CSRRWI, CSRRSI, CSRRCI
  )
}

// -------- DecodeField helpers --------

trait DecodeAPI {
  def bitPatFor[D <: chisel3.Data](v: D): BitPat = BitPat(v.litValue.U(v.getWidth.W))
}

// -------- DecodeFields --------

/** Outputs UInt(width) so decode never casts to ImmType (avoids W001); use ImmType.safe() when reading. */
object ImmTypeField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "imm_type"
  def chiselType = UInt(ImmType.getWidth.W)
  def genTable(inst: RVInst): BitPat = inst.immType.fold(BitPat.dontCare(ImmType.getWidth))(bitPatFor)
}

object FuTypeField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "fu_type"
  def chiselType = UInt(FuType.getWidth.W)
  def genTable(inst: RVInst): BitPat = bitPatFor(inst.fuType)
}

object AluOpField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "alu_op"
  def chiselType = UInt(AluOp.getWidth.W)
  def genTable(inst: RVInst): BitPat = inst.aluOp.fold(BitPat.dontCare(AluOp.getWidth))(bitPatFor)
}

/** All decode fields with defaults; decode in one pass (one decoder call per field). */
object DecodeFields {
  val allWithDefaults: Seq[(DecodeField[RVInst, UInt], BitPat)] = Seq(
    (ImmTypeField, BitPat(ImmType.I.litValue.U(ImmType.getWidth.W))),
    (FuTypeField, BitPat(FuType.ALU.litValue.U(FuType.getWidth.W))),
    (AluOpField, BitPat(AluOp.Add.litValue.U(AluOp.getWidth.W)))
  )

  def decodeAll(allInsts: Seq[RVInst], inst: chisel3.UInt, specs: Seq[(DecodeField[RVInst, UInt], BitPat)]): Seq[chisel3.UInt] =
    specs.map { case (field, default) =>
      val mapping = allInsts.map(p => (p.bitPat, field.genTable(p)))
      val table   = TruthTable(mapping, default)
      decoder(QMCMinimizer, inst, table)
    }
}
