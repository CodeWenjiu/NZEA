package nzea_core.frontend

import chisel3._
import chisel3.util.BitPat
import chisel3.util.experimental.decode.{DecodeField, DecodePattern, TruthTable, QMCMinimizer, decoder}
import nzea_core.backend.FuOpWidth
import nzea_core.backend.fu.{AluOp, BruOp}
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
  case class BRU(op: BruOp.Type) extends Type
  case object LSU  extends Type
  case object SYSU extends Type

  def fuTypeOf(fuOp: FuOp.Type): FuType.Type = fuOp match {
    case ALU(_) => FuType.ALU
    case BRU(_) => FuType.BRU
    case LSU    => FuType.LSU
    case SYSU   => FuType.SYSU
  }
  def toLitValue(fuOp: FuOp.Type): BigInt = fuOp match {
    case ALU(op) => op.litValue
    case BRU(op) => op.litValue
    case _       => BigInt(0)
  }
}

/** ALU operand source for opA/opB; ISU routes by this. Encoding: 0=Rs1Rs2, 1=Rs1Imm, 2=ImmZero, 3=PcImm. */
object AluSrc extends chisel3.ChiselEnum {
  val Rs1Rs2  = Value
  val Rs1Imm  = Value
  val ImmZero = Value
  val PcImm   = Value
}

/** BRU target source: 0=PcImm (pc+imm), 1=Rs1Imm (rs1+imm for JALR). */
object BruSrc extends chisel3.ChiselEnum {
  val PcImm  = Value
  val Rs1Imm = Value
}

/** LSU address source: base + offset. */
object LsuSrc extends chisel3.ChiselEnum {
  val Rs1Imm = Value
}

/** Union of all FU src enums; decode outputs fu_src, ISU interprets by fu_type as the corresponding ChiselEnum. */
object FuSrc {
  sealed trait Type
  case class ALU(src: AluSrc.Type)  extends Type
  case class BRU(src: BruSrc.Type)  extends Type
  case class LSU(src: LsuSrc.Type)  extends Type
  case object SYSU                  extends Type

  def toLitValue(t: FuSrc.Type): BigInt = t match {
    case ALU(s)  => s.litValue
    case BRU(s)  => s.litValue
    case LSU(s)  => s.litValue
    case SYSU   => BigInt(0)
  }
}

/** fu_src width: max of all FU src enum widths; when adding/changing FUs, update this Seq and the ChiselEnums only. */
object FuSrcWidth {
  val Width: Int = Seq(AluSrc.getWidth, BruSrc.getWidth, LsuSrc.getWidth).max
}

/** Slice unified fu_src/fu_op by enum width for each FU's .safe(); use take(fu_*, X.getWidth) in ISU, no manual bit-width. */
object FuDecode {
  def take(u: UInt, width: Int): UInt = u(width - 1, 0)
}

/** RISC-V instruction pattern: fuOp + fuSrc; decode yields fu_type, fu_op, fu_src. */
case class RVInst(
  name: String,
  bitPatStr: String,
  immType: Option[ImmType.Type],
  fuOp: FuOp.Type,
  fuSrc: FuSrc.Type
) extends DecodePattern {
  def bitPat: BitPat = BitPat(bitPatStr)
}

/** RV32I instruction set. Bit layout: [31:25] funct7, [24:20] rs2, [19:15] rs1, [14:12] funct3, [11:7] rd, [6:0] opcode. */
object RiscvInsts {
  def n(n: Int) = "?" * n

  // U-type: LUI=0110111, AUIPC=0010111
  val LUI   = RVInst("LUI",   "b" + n(25) + "0110111", Some(ImmType.U), FuOp.ALU(AluOp.Add), FuSrc.ALU(AluSrc.ImmZero))
  val AUIPC = RVInst("AUIPC", "b" + n(25) + "0010111", Some(ImmType.U), FuOp.ALU(AluOp.Add), FuSrc.ALU(AluSrc.PcImm))

  // J-type: JAL=1101111
  val JAL   = RVInst("JAL",   "b" + n(25) + "1101111", Some(ImmType.J), FuOp.BRU(BruOp.JAL), FuSrc.BRU(BruSrc.PcImm))

  // I-type
  val JALR  = RVInst("JALR",  "b" + n(12) + n(5) + "000" + n(5) + "1100111", Some(ImmType.I), FuOp.BRU(BruOp.JALR), FuSrc.BRU(BruSrc.Rs1Imm))
  val LB    = RVInst("LB",    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, FuSrc.LSU(LsuSrc.Rs1Imm))
  val LH    = RVInst("LH",    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, FuSrc.LSU(LsuSrc.Rs1Imm))
  val LW    = RVInst("LW",    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, FuSrc.LSU(LsuSrc.Rs1Imm))
  val LBU   = RVInst("LBU",   "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, FuSrc.LSU(LsuSrc.Rs1Imm))
  val LHU   = RVInst("LHU",   "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011", Some(ImmType.I), FuOp.LSU, FuSrc.LSU(LsuSrc.Rs1Imm))
  val ADDI  = RVInst("ADDI",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Add), FuSrc.ALU(AluSrc.Rs1Imm))
  val SLTI  = RVInst("SLTI",  "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Slt), FuSrc.ALU(AluSrc.Rs1Imm))
  val SLTIU = RVInst("SLTIU", "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Sltu), FuSrc.ALU(AluSrc.Rs1Imm))
  val XORI  = RVInst("XORI",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Xor), FuSrc.ALU(AluSrc.Rs1Imm))
  val ORI   = RVInst("ORI",   "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Or), FuSrc.ALU(AluSrc.Rs1Imm))
  val ANDI  = RVInst("ANDI",  "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.And), FuSrc.ALU(AluSrc.Rs1Imm))
  val SLLI  = RVInst("SLLI",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Sll), FuSrc.ALU(AluSrc.Rs1Imm))
  val SRLI  = RVInst("SRLI",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Srl), FuSrc.ALU(AluSrc.Rs1Imm))
  val SRAI  = RVInst("SRAI",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011", Some(ImmType.I), FuOp.ALU(AluOp.Sra), FuSrc.ALU(AluSrc.Rs1Imm))
  val ECALL = RVInst("ECALL", "b00000000000000000000000001110011", Some(ImmType.I), FuOp.SYSU, FuSrc.SYSU)
  val EBREAK= RVInst("EBREAK","b00000000000100000000000001110011", Some(ImmType.I), FuOp.SYSU, FuSrc.SYSU)
  val CSRRW  = RVInst("CSRRW",  "b" + n(12) + n(5) + "001" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, FuSrc.SYSU)
  val CSRRS  = RVInst("CSRRS",  "b" + n(12) + n(5) + "010" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, FuSrc.SYSU)
  val CSRRC  = RVInst("CSRRC",  "b" + n(12) + n(5) + "011" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, FuSrc.SYSU)
  val CSRRWI = RVInst("CSRRWI", "b" + n(12) + n(5) + "101" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, FuSrc.SYSU)
  val CSRRSI = RVInst("CSRRSI", "b" + n(12) + n(5) + "110" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, FuSrc.SYSU)
  val CSRRCI = RVInst("CSRRCI", "b" + n(12) + n(5) + "111" + n(5) + "1110011", Some(ImmType.I), FuOp.SYSU, FuSrc.SYSU)

  // S-type
  val SB = RVInst("SB", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0100011", Some(ImmType.S), FuOp.LSU, FuSrc.LSU(LsuSrc.Rs1Imm))
  val SH = RVInst("SH", "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0100011", Some(ImmType.S), FuOp.LSU, FuSrc.LSU(LsuSrc.Rs1Imm))
  val SW = RVInst("SW", "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0100011", Some(ImmType.S), FuOp.LSU, FuSrc.LSU(LsuSrc.Rs1Imm))

  // B-type
  val BEQ  = RVInst("BEQ",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU(BruOp.BEQ), FuSrc.BRU(BruSrc.PcImm))
  val BNE  = RVInst("BNE",  "b" + n(7) + n(5) + n(5) + "001" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU(BruOp.BNE), FuSrc.BRU(BruSrc.PcImm))
  val BLT  = RVInst("BLT",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU(BruOp.BLT), FuSrc.BRU(BruSrc.PcImm))
  val BGE  = RVInst("BGE",  "b" + n(7) + n(5) + n(5) + "101" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU(BruOp.BGE), FuSrc.BRU(BruSrc.PcImm))
  val BLTU = RVInst("BLTU", "b" + n(7) + n(5) + n(5) + "110" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU(BruOp.BLTU), FuSrc.BRU(BruSrc.PcImm))
  val BGEU = RVInst("BGEU", "b" + n(7) + n(5) + n(5) + "111" + n(5) + "1100011", Some(ImmType.B), FuOp.BRU(BruOp.BGEU), FuSrc.BRU(BruSrc.PcImm))

  // R-type
  val ADD  = RVInst("ADD",  "b0000000" + n(5) + n(5) + "000" + n(5) + "0110011", None, FuOp.ALU(AluOp.Add), FuSrc.ALU(AluSrc.Rs1Rs2))
  val SUB  = RVInst("SUB",  "b0100000" + n(5) + n(5) + "000" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sub), FuSrc.ALU(AluSrc.Rs1Rs2))
  val SLL  = RVInst("SLL",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sll), FuSrc.ALU(AluSrc.Rs1Rs2))
  val SLT  = RVInst("SLT",  "b0000000" + n(5) + n(5) + "010" + n(5) + "0110011", None, FuOp.ALU(AluOp.Slt), FuSrc.ALU(AluSrc.Rs1Rs2))
  val SLTU = RVInst("SLTU", "b0000000" + n(5) + n(5) + "011" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sltu), FuSrc.ALU(AluSrc.Rs1Rs2))
  val XOR  = RVInst("XOR",  "b0000000" + n(5) + n(5) + "100" + n(5) + "0110011", None, FuOp.ALU(AluOp.Xor), FuSrc.ALU(AluSrc.Rs1Rs2))
  val SRL  = RVInst("SRL",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0110011", None, FuOp.ALU(AluOp.Srl), FuSrc.ALU(AluSrc.Rs1Rs2))
  val SRA  = RVInst("SRA",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0110011", None, FuOp.ALU(AluOp.Sra), FuSrc.ALU(AluSrc.Rs1Rs2))
  val OR   = RVInst("OR",   "b0000000" + n(5) + n(5) + "110" + n(5) + "0110011", None, FuOp.ALU(AluOp.Or), FuSrc.ALU(AluSrc.Rs1Rs2))
  val AND  = RVInst("AND",  "b0000000" + n(5) + n(5) + "111" + n(5) + "0110011", None, FuOp.ALU(AluOp.And), FuSrc.ALU(AluSrc.Rs1Rs2))

  val FENCE = RVInst("FENCE", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0001111", None, FuOp.SYSU, FuSrc.SYSU)

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

object FuSrcField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "fu_src"
  def chiselType = UInt(FuSrcWidth.Width.W)
  def genTable(inst: RVInst): BitPat = BitPat(FuSrc.toLitValue(inst.fuSrc).U(FuSrcWidth.Width.W))
}

/** All decode fields with defaults; decode in one pass (one decoder call per field). */
object DecodeFields {
  val allWithDefaults: Seq[(DecodeField[RVInst, UInt], BitPat)] = Seq(
    (ImmTypeField, BitPat(ImmType.I.litValue.U(ImmType.getWidth.W))),
    (FuTypeField, BitPat(FuType.ALU.litValue.U(FuType.getWidth.W))),
    (FuOpField, BitPat(0.U(FuOpWidth.Width.W))),
    (FuSrcField, BitPat(0.U(FuSrcWidth.Width.W)))
  )

  def decodeAll(allInsts: Seq[RVInst], inst: chisel3.UInt, specs: Seq[(DecodeField[RVInst, UInt], BitPat)]): Seq[chisel3.UInt] =
    specs.map { case (field, default) =>
      val mapping = allInsts.map(p => (p.bitPat, field.genTable(p)))
      val table   = TruthTable(mapping, default)
      decoder(QMCMinimizer, inst, table)
    }
}
