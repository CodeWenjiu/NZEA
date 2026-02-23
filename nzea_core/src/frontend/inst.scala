package nzea_core.frontend

import chisel3._
import chisel3.util.BitPat
import chisel3.util.experimental.decode.{DecodeField, DecodePattern, TruthTable, QMCMinimizer, decoder}
import nzea_core.backend.fu.{FuOpWidth, AluOp}
// -------- Instruction pattern & decode fields (ImmType, FuOp, RVInst, RiscvInsts) --------

/** One-hot encoding for Mux1H; better timing than binary. */
object ImmType extends chisel3.ChiselEnum {
  val I = Value((1 << 0).U)
  val S = Value((1 << 1).U)
  val B = Value((1 << 2).U)
  val U = Value((1 << 3).U)
  val J = Value((1 << 4).U)
}

/** Function unit type for routing (ALU/BRU/LSU/SYSU). */
object FuType extends chisel3.ChiselEnum {
  val ALU  = Value
  val BRU  = Value
  val LSU  = Value
  val SYSU = Value
}

/** Big enum: which FU + that FU's ChiselEnum. Decode: fu_type := match fuOp; fu_op := fuOp.into() as unified UInt. */
object FuOp {
  sealed trait Type
  case class ALU(op: AluOp.Type) extends Type
  case object BRU  extends Type
  case object LSU  extends Type
  case object SYSU extends Type

  def fuTypeOf(fuOp: FuOp.Type): FuType.Type = fuOp match {
    case ALU(_) => FuType.ALU
    case BRU    => FuType.BRU
    case LSU    => FuType.LSU
    case SYSU   => FuType.SYSU
  }
  def toLitValue(fuOp: FuOp.Type): BigInt = fuOp match {
    case ALU(op) => op.litValue
    case _       => BigInt(0)
  }
}

/** ISU pre-dispatch control big enum: interpreted by fu_type; one isu_ctrl field, width = max over FUs. */
object IsuCtrl {
  sealed trait Type
  case class ALU(ctrl: AluIsuCtrl.Type) extends Type
  case object None extends Type

  def toLitValue(c: IsuCtrl.Type): BigInt = c match {
    case ALU(ctrl) => ctrl.litValue
    case None      => BigInt(0)
  }
}

/** RISC-V instruction pattern: fuOp + isuCtrl; fu_type, fu_op, isu_ctrl are derived from decode table. */
case class RVInst(
  name: String,
  bitPatStr: String,
  immType: Option[ImmType.Type],
  fuOp: FuOp.Type,
  isuCtrl: IsuCtrl.Type
) extends DecodePattern {
  def bitPat: BitPat = BitPat(bitPatStr)
}

/** RV32I instruction set. Bit layout: [31:25] funct7, [24:20] rs2, [19:15] rs1, [14:12] funct3, [11:7] rd, [6:0] opcode. */
object RiscvInsts {
  def n(n: Int) = "?" * n

  // U-type: LUI=0110111, AUIPC=0010111
  val LUI   = RVInst("LUI",   "b" + n(25) + "0110111", Some(ImmType.U), FuOp.ALU(AluOp.Add), IsuCtrl.ALU(AluIsuCtrl.ImmZero))
  val AUIPC = RVInst("AUIPC", "b" + n(25) + "0010111", Some(ImmType.U), FuOp.ALU(AluOp.Add), IsuCtrl.ALU(AluIsuCtrl.PcImm))

  // J-type: JAL=1101111
  val JAL   = RVInst("JAL",   "b" + n(25) + "1101111", Some(ImmType.J), FuOp.BRU, IsuCtrl.None)

  // I-type
  val JALR  = RVInst("JALR",  "b" + n(12) + n(5) + "000" + n(5) + "1100111", Some(ImmType.I), FuOp.BRU, IsuCtrl.None)
  val LB    = RVInst("LB",    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, IsuCtrl.None)
  val LH    = RVInst("LH",    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, IsuCtrl.None)
  val LW    = RVInst("LW",    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, IsuCtrl.None)
  val LBU   = RVInst("LBU",   "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, IsuCtrl.None)
  val LHU   = RVInst("LHU",   "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, IsuCtrl.None)
  val ADDI  = RVInst("ADDI",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Add), IsuCtrl.ALU(AluIsuCtrl.Rs1Imm))
  val SLTI  = RVInst("SLTI",  "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Add), IsuCtrl.ALU(AluIsuCtrl.SltImm))
  val SLTIU = RVInst("SLTIU", "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Add), IsuCtrl.ALU(AluIsuCtrl.SltuImm))
  val XORI  = RVInst("XORI",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Xor), IsuCtrl.ALU(AluIsuCtrl.Rs1Imm))
  val ORI   = RVInst("ORI",   "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Or), IsuCtrl.ALU(AluIsuCtrl.Rs1Imm))
  val ANDI  = RVInst("ANDI",  "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.And), IsuCtrl.ALU(AluIsuCtrl.Rs1Imm))
  val SLLI  = RVInst("SLLI",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Sll), IsuCtrl.ALU(AluIsuCtrl.Rs1Imm))
  val SRLI  = RVInst("SRLI",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Srl), IsuCtrl.ALU(AluIsuCtrl.Rs1Imm))
  val SRAI  = RVInst("SRAI",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Sra), IsuCtrl.ALU(AluIsuCtrl.Rs1Imm))
  val ECALL = RVInst("ECALL", "b00000000000000000000000001110011", Some(ImmType.I), FuOp.SYSU, IsuCtrl.None)
  val EBREAK= RVInst("EBREAK","b00000000000100000000000001110011", Some(ImmType.I), FuOp.SYSU, IsuCtrl.None)
  val CSRRW  = RVInst("CSRRW",  "b" + n(12) + n(5) + "001" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, IsuCtrl.None)
  val CSRRS  = RVInst("CSRRS",  "b" + n(12) + n(5) + "010" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, IsuCtrl.None)
  val CSRRC  = RVInst("CSRRC",  "b" + n(12) + n(5) + "011" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, IsuCtrl.None)
  val CSRRWI = RVInst("CSRRWI", "b" + n(12) + n(5) + "101" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, IsuCtrl.None)
  val CSRRSI = RVInst("CSRRSI", "b" + n(12) + n(5) + "110" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, IsuCtrl.None)
  val CSRRCI = RVInst("CSRRCI", "b" + n(12) + n(5) + "111" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, IsuCtrl.None)

  // S-type
  val SB = RVInst("SB", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0100011", Some(ImmType.S), FuOp.LSU, IsuCtrl.None)
  val SH = RVInst("SH", "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0100011", Some(ImmType.S), FuOp.LSU, IsuCtrl.None)
  val SW = RVInst("SW", "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0100011", Some(ImmType.S), FuOp.LSU, IsuCtrl.None)

  // B-type
  val BEQ  = RVInst("BEQ",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU, IsuCtrl.None)
  val BNE  = RVInst("BNE",  "b" + n(7) + n(5) + n(5) + "001" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU, IsuCtrl.None)
  val BLT  = RVInst("BLT",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU, IsuCtrl.None)
  val BGE  = RVInst("BGE",  "b" + n(7) + n(5) + n(5) + "101" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU, IsuCtrl.None)
  val BLTU = RVInst("BLTU", "b" + n(7) + n(5) + n(5) + "110" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU, IsuCtrl.None)
  val BGEU = RVInst("BGEU", "b" + n(7) + n(5) + n(5) + "111" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU, IsuCtrl.None)

  // R-type
  val ADD  = RVInst("ADD",  "b0000000" + n(5) + n(5) + "000" + n(5) + "0110011", None, FuOp.ALU(AluOp.Add), IsuCtrl.ALU(AluIsuCtrl.Rs1Rs2))
  val SUB  = RVInst("SUB",  "b0100000" + n(5) + n(5) + "000" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sub), IsuCtrl.ALU(AluIsuCtrl.Rs1Rs2))
  val SLL  = RVInst("SLL",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sll), IsuCtrl.ALU(AluIsuCtrl.Rs1Rs2))
  val SLT  = RVInst("SLT",  "b0000000" + n(5) + n(5) + "010" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sub), IsuCtrl.ALU(AluIsuCtrl.Slt))
  val SLTU = RVInst("SLTU", "b0000000" + n(5) + n(5) + "011" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sub), IsuCtrl.ALU(AluIsuCtrl.Sltu))
  val XOR  = RVInst("XOR",  "b0000000" + n(5) + n(5) + "100" + n(5) + "0110011", None, FuOp.ALU(AluOp.Xor), IsuCtrl.ALU(AluIsuCtrl.Rs1Rs2))
  val SRL  = RVInst("SRL",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0110011", None, FuOp.ALU(AluOp.Srl), IsuCtrl.ALU(AluIsuCtrl.Rs1Rs2))
  val SRA  = RVInst("SRA",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sra), IsuCtrl.ALU(AluIsuCtrl.Rs1Rs2))
  val OR   = RVInst("OR",   "b0000000" + n(5) + n(5) + "110" + n(5) + "0110011", None, FuOp.ALU(AluOp.Or), IsuCtrl.ALU(AluIsuCtrl.Rs1Rs2))
  val AND  = RVInst("AND",  "b0000000" + n(5) + n(5) + "111" + n(5) + "0110011", None, FuOp.ALU(AluOp.And), IsuCtrl.ALU(AluIsuCtrl.Rs1Rs2))

  val FENCE = RVInst("FENCE", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0001111", None, FuOp.SYSU, IsuCtrl.None)

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
  def genTable(inst: RVInst): BitPat = bitPatFor(FuOp.fuTypeOf(inst.fuOp))
}

object FuOpField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "fu_op"
  def chiselType = UInt(FuOpWidth.Width.W)
  def genTable(inst: RVInst): BitPat = BitPat(FuOp.toLitValue(inst.fuOp).U(FuOpWidth.Width.W))
}

object IsuCtrlField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "isu_ctrl"
  def chiselType = UInt(IsuCtrlWidth.Width.W)
  def genTable(inst: RVInst): BitPat = BitPat(IsuCtrl.toLitValue(inst.isuCtrl).U(IsuCtrlWidth.Width.W))
}

/** All decode fields with defaults; decode in one pass (one decoder call per field). */
object DecodeFields {
  val allWithDefaults: Seq[(DecodeField[RVInst, UInt], BitPat)] = Seq(
    (ImmTypeField, BitPat(ImmType.I.litValue.U(ImmType.getWidth.W))),
    (FuTypeField, BitPat(FuType.ALU.litValue.U(FuType.getWidth.W))),
    (FuOpField, BitPat(0.U(FuOpWidth.Width.W))),
    (IsuCtrlField, BitPat(0.U(IsuCtrlWidth.Width.W)))
  )

  def decodeAll(allInsts: Seq[RVInst], inst: chisel3.UInt, specs: Seq[(DecodeField[RVInst, UInt], BitPat)]): Seq[chisel3.UInt] =
    specs.map { case (field, default) =>
      val mapping = allInsts.map(p => (p.bitPat, field.genTable(p)))
      val table   = TruthTable(mapping, default)
      decoder(QMCMinimizer, inst, table)
    }
}
