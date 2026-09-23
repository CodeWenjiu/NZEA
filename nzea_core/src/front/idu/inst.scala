package nzea_core.front.idu

import chisel3._
import chisel3.util.{BitPat, Mux1H, MuxCase}
import chisel3.util.experimental.decode.{decoder, DecodeField, DecodePattern, QMCMinimizer, TruthTable}
import nzea_core.isa.{AluOp, AluSrc, BruOp, BruSrc, DivOp, Fu, FuDecode, FuOpWidth, FuSrcWidth, FuType, LsuOp, LsuSrc, MulOp, SysuOp}
import nzea_config.core.CoreConfig

// -------- Instruction pattern & decode fields (RVInst, RiscvInsts) --------

/** One-hot encoding for Mux1H; better timing than binary. */
object ImmType extends chisel3.ChiselEnum {
  val I = Value((1 << 0).U)
  val S = Value((1 << 1).U)
  val B = Value((1 << 2).U)
  val U = Value((1 << 3).U)
  val J = Value((1 << 4).U)
  val Z = Value((1 << 5).U) // zero-extend inst[19:15] for CSR zimm
}

/** RISC-V instruction pattern: single fu (op+src); decode yields fu_type, fu_op, fu_src, gpr_wr (true if instruction
  * writes GPR).
  */
case class RVInst(
    name: String,
    bitPatStr: String,
    immType: Option[ImmType.Type],
    fu: Fu.Type,
    gprWr: Boolean = true,
    rs1Rd: Boolean = true,
    rs2Rd: Boolean = true
) extends DecodePattern {
  def bitPat: BitPat = BitPat(bitPatStr)
}

/** RV32I instruction set. Bit layout: [31:25] funct7, [24:20] rs2, [19:15] rs1, [14:12] funct3, [11:7] rd, [6:0]
  * opcode.
  */
object RiscvInsts {
  def n(n: Int) = "?" * n

  // U-type: LUI=0110111, AUIPC=0010111
  val LUI = RVInst(
    "LUI",
    "b" + n(25) + "0110111",
    Some(ImmType.U),
    Fu.ALU(AluOp.Add, AluSrc.ImmZero),
    rs1Rd = false,
    rs2Rd = false
  )

  val AUIPC = RVInst(
    "AUIPC",
    "b" + n(25) + "0010111",
    Some(ImmType.U),
    Fu.ALU(AluOp.Add, AluSrc.PcImm),
    rs1Rd = false,
    rs2Rd = false
  )

  // J-type: JAL=1101111
  val JAL = RVInst(
    "JAL",
    "b" + n(25) + "1101111",
    Some(ImmType.J),
    Fu.BRU(BruOp.JAL, BruSrc.PcImm),
    rs1Rd = false,
    rs2Rd = false
  )

  // I-type
  val JALR = RVInst(
    "JALR",
    "b" + n(12) + n(5) + "000" + n(5) + "1100111",
    Some(ImmType.I),
    Fu.BRU(BruOp.JALR, BruSrc.Rs1Imm),
    rs2Rd = false
  )

  val LB = RVInst(
    "LB",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011",
    Some(ImmType.I),
    Fu.LSU(LsuOp.LB, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val LH = RVInst(
    "LH",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011",
    Some(ImmType.I),
    Fu.LSU(LsuOp.LH, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val LW = RVInst(
    "LW",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011",
    Some(ImmType.I),
    Fu.LSU(LsuOp.LW, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val LBU = RVInst(
    "LBU",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011",
    Some(ImmType.I),
    Fu.LSU(LsuOp.LBU, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val LHU = RVInst(
    "LHU",
    "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011",
    Some(ImmType.I),
    Fu.LSU(LsuOp.LHU, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val ADDI = RVInst(
    "ADDI",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.Add, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SLTI = RVInst(
    "SLTI",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.Slt, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SLTIU = RVInst(
    "SLTIU",
    "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.Sltu, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val XORI = RVInst(
    "XORI",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.Xor, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val ORI = RVInst(
    "ORI",
    "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.Or, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val ANDI = RVInst(
    "ANDI",
    "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.And, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SLLI = RVInst(
    "SLLI",
    "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.Sll, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SRLI = RVInst(
    "SRLI",
    "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.Srl, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SRAI = RVInst(
    "SRAI",
    "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Fu.ALU(AluOp.Sra, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val ECALL = RVInst(
    "ECALL",
    "b00000000000000000000000001110011",
    Some(ImmType.I),
    Fu.SYSU(SysuOp.ECALL),
    gprWr = false,
    rs1Rd = false,
    rs2Rd = false
  )

  val EBREAK = RVInst(
    "EBREAK",
    "b00000000000100000000000001110011",
    Some(ImmType.I),
    Fu.SYSU(SysuOp.EBREAK),
    gprWr = false,
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRW = RVInst(
    "CSRRW",
    "b" + n(12) + n(5) + "001" + n(5) + "1110011",
    Some(ImmType.I),
    Fu.SYSU(SysuOp.CSRRW)
  )

  val CSRRS = RVInst(
    "CSRRS",
    "b" + n(12) + n(5) + "010" + n(5) + "1110011",
    Some(ImmType.I),
    Fu.SYSU(SysuOp.CSRRS)
  )

  val CSRRC = RVInst(
    "CSRRC",
    "b" + n(12) + n(5) + "011" + n(5) + "1110011",
    Some(ImmType.I),
    Fu.SYSU(SysuOp.CSRRC)
  )

  val CSRRWI = RVInst(
    "CSRRWI",
    "b" + n(12) + n(5) + "101" + n(5) + "1110011",
    Some(ImmType.Z),
    Fu.SYSU(SysuOp.CSRRWI),
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRSI = RVInst(
    "CSRRSI",
    "b" + n(12) + n(5) + "110" + n(5) + "1110011",
    Some(ImmType.Z),
    Fu.SYSU(SysuOp.CSRRSI),
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRCI = RVInst(
    "CSRRCI",
    "b" + n(12) + n(5) + "111" + n(5) + "1110011",
    Some(ImmType.Z),
    Fu.SYSU(SysuOp.CSRRCI),
    rs1Rd = false,
    rs2Rd = false
  )

  // S-type: rs1=base, rs2=data; no rd
  val SB = RVInst(
    "SB",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0100011",
    Some(ImmType.S),
    Fu.LSU(LsuOp.SB, LsuSrc.Rs1Imm),
    gprWr = false
  )

  val SH = RVInst(
    "SH",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0100011",
    Some(ImmType.S),
    Fu.LSU(LsuOp.SH, LsuSrc.Rs1Imm),
    gprWr = false
  )

  val SW = RVInst(
    "SW",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0100011",
    Some(ImmType.S),
    Fu.LSU(LsuOp.SW, LsuSrc.Rs1Imm),
    gprWr = false
  )

  // B-type: rs1, rs2 for compare; no rd
  val BEQ = RVInst(
    "BEQ",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "1100011",
    Some(ImmType.B),
    Fu.BRU(BruOp.BEQ, BruSrc.PcImm),
    gprWr = false
  )

  val BNE = RVInst(
    "BNE",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "1100011",
    Some(ImmType.B),
    Fu.BRU(BruOp.BNE, BruSrc.PcImm),
    gprWr = false
  )

  val BLT = RVInst(
    "BLT",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "1100011",
    Some(ImmType.B),
    Fu.BRU(BruOp.BLT, BruSrc.PcImm),
    gprWr = false
  )

  val BGE = RVInst(
    "BGE",
    "b" + n(7) + n(5) + n(5) + "101" + n(5) + "1100011",
    Some(ImmType.B),
    Fu.BRU(BruOp.BGE, BruSrc.PcImm),
    gprWr = false
  )

  val BLTU = RVInst(
    "BLTU",
    "b" + n(7) + n(5) + n(5) + "110" + n(5) + "1100011",
    Some(ImmType.B),
    Fu.BRU(BruOp.BLTU, BruSrc.PcImm),
    gprWr = false
  )

  val BGEU = RVInst(
    "BGEU",
    "b" + n(7) + n(5) + n(5) + "111" + n(5) + "1100011",
    Some(ImmType.B),
    Fu.BRU(BruOp.BGEU, BruSrc.PcImm),
    gprWr = false
  )

  // R-type: rs1, rs2
  val ADD = RVInst(
    "ADD",
    "b0000000" + n(5) + n(5) + "000" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Add, AluSrc.Rs1Rs2)
  )

  val SUB = RVInst(
    "SUB",
    "b0100000" + n(5) + n(5) + "000" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Sub, AluSrc.Rs1Rs2)
  )

  val SLL = RVInst(
    "SLL",
    "b0000000" + n(5) + n(5) + "001" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Sll, AluSrc.Rs1Rs2)
  )

  val SLT = RVInst(
    "SLT",
    "b0000000" + n(5) + n(5) + "010" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Slt, AluSrc.Rs1Rs2)
  )

  val SLTU = RVInst(
    "SLTU",
    "b0000000" + n(5) + n(5) + "011" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Sltu, AluSrc.Rs1Rs2)
  )

  val XOR = RVInst(
    "XOR",
    "b0000000" + n(5) + n(5) + "100" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Xor, AluSrc.Rs1Rs2)
  )

  val SRL = RVInst(
    "SRL",
    "b0000000" + n(5) + n(5) + "101" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Srl, AluSrc.Rs1Rs2)
  )

  val SRA = RVInst(
    "SRA",
    "b0100000" + n(5) + n(5) + "101" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Sra, AluSrc.Rs1Rs2)
  )

  val OR = RVInst(
    "OR",
    "b0000000" + n(5) + n(5) + "110" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.Or, AluSrc.Rs1Rs2)
  )

  val AND = RVInst(
    "AND",
    "b0000000" + n(5) + n(5) + "111" + n(5) + "0110011",
    None,
    Fu.ALU(AluOp.And, AluSrc.Rs1Rs2)
  )

  // M extension: funct7=0000001
  val MUL = RVInst(
    "MUL",
    "b0000001" + n(5) + n(5) + "000" + n(5) + "0110011",
    None,
    Fu.MUL(MulOp.Mul)
  )

  val MULH = RVInst(
    "MULH",
    "b0000001" + n(5) + n(5) + "001" + n(5) + "0110011",
    None,
    Fu.MUL(MulOp.Mulh)
  )

  val MULHSU = RVInst(
    "MULHSU",
    "b0000001" + n(5) + n(5) + "010" + n(5) + "0110011",
    None,
    Fu.MUL(MulOp.Mulhsu)
  )

  val MULHU = RVInst(
    "MULHU",
    "b0000001" + n(5) + n(5) + "011" + n(5) + "0110011",
    None,
    Fu.MUL(MulOp.Mulhu)
  )

  val DIV = RVInst(
    "DIV",
    "b0000001" + n(5) + n(5) + "100" + n(5) + "0110011",
    None,
    Fu.DIV(DivOp.Div)
  )

  val DIVU = RVInst(
    "DIVU",
    "b0000001" + n(5) + n(5) + "101" + n(5) + "0110011",
    None,
    Fu.DIV(DivOp.Divu)
  )

  val REM = RVInst(
    "REM",
    "b0000001" + n(5) + n(5) + "110" + n(5) + "0110011",
    None,
    Fu.DIV(DivOp.Rem)
  )

  val REMU = RVInst(
    "REMU",
    "b0000001" + n(5) + n(5) + "111" + n(5) + "0110011",
    None,
    Fu.DIV(DivOp.Remu)
  )

  val FENCE = RVInst(
    "FENCE",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0001111",
    None,
    Fu.SYSU(SysuOp.FENCE),
    gprWr = false,
    rs1Rd = false,
    rs2Rd = false
  )

  private val base: Seq[RVInst] = Seq(
    LUI,
    AUIPC,
    JAL,
    JALR,
    BEQ,
    BNE,
    BLT,
    BGE,
    BLTU,
    BGEU,
    LB,
    LH,
    LW,
    LBU,
    LHU,
    SB,
    SH,
    SW,
    ADDI,
    SLTI,
    SLTIU,
    XORI,
    ORI,
    ANDI,
    SLLI,
    SRLI,
    SRAI,
    ADD,
    SUB,
    SLL,
    SLT,
    SLTU,
    XOR,
    SRL,
    SRA,
    OR,
    AND,
    FENCE,
    ECALL,
    EBREAK,
    CSRRW,
    CSRRS,
    CSRRC,
    CSRRWI,
    CSRRSI,
    CSRRCI
  )

  private val m: Seq[RVInst] = Seq(MUL, MULH, MULHSU, MULHU, DIV, DIVU, REM, REMU)

  /** All instructions for decode; M gated like other optional extensions. */
  def all(implicit config: CoreConfig): Seq[RVInst] =
    base ++ (if (config.isaConfig.hasM) m else Seq.empty)

}

// -------- DecodeField helpers --------

trait DecodeAPI {

  def bitPatFor[D <: chisel3.Data](v: D): BitPat = BitPat(
    v.litValue.U(v.getWidth.W)
  )

}

// -------- DecodeFields --------

/** Outputs UInt(width) so decode never casts to ImmType (avoids W001); use ImmType.safe() when reading.
  */
object ImmTypeField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "imm_type"
  def chiselType = UInt(ImmType.getWidth.W)

  def genTable(inst: RVInst): BitPat =
    inst.immType.fold(BitPat.dontCare(ImmType.getWidth))(bitPatFor)

}

object FuTypeField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "fu_type"
  def chiselType = UInt(FuType.getWidth.W)
  def genTable(inst: RVInst): BitPat = bitPatFor(Fu.fuTypeOf(inst.fu))
}

object FuOpField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "fu_op"
  def chiselType = UInt(FuOpWidth.Width.W)

  def genTable(inst: RVInst): BitPat = BitPat(
    Fu.opLitValue(inst.fu).U(FuOpWidth.Width.W)
  )

}

object FuSrcField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "fu_src"
  def chiselType = UInt(FuSrcWidth.Width.W)

  def genTable(inst: RVInst): BitPat = BitPat(
    Fu.srcLitValue(inst.fu).U(FuSrcWidth.Width.W)
  )

}

object GprWrField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "gpr_wr"
  def chiselType = UInt(1.W)
  def genTable(inst: RVInst): BitPat = BitPat("b" + (if (inst.gprWr) "1" else "0"))
}

object Rs1RdField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "rs1_rd"
  def chiselType = UInt(1.W)
  def genTable(inst: RVInst): BitPat = BitPat("b" + (if (inst.rs1Rd) "1" else "0"))
}

object Rs2RdField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "rs2_rd"
  def chiselType = UInt(1.W)
  def genTable(inst: RVInst): BitPat = BitPat("b" + (if (inst.rs2Rd) "1" else "0"))
}

/** All decode fields with defaults; decode in one pass (one decoder call per field).
  */
object DecodeFields {

  val allWithDefaults: Seq[(DecodeField[RVInst, UInt], BitPat)] = Seq(
    (ImmTypeField, BitPat(ImmType.I.litValue.U(ImmType.getWidth.W))),
    (FuTypeField, BitPat(FuType.ALU.litValue.U(FuType.getWidth.W))),
    (FuOpField, BitPat(0.U(FuOpWidth.Width.W))),
    (FuSrcField, BitPat(0.U(FuSrcWidth.Width.W))),
    (GprWrField, BitPat(0.U(1.W))),
    (Rs1RdField, BitPat(0.U(1.W))),
    (Rs2RdField, BitPat(0.U(1.W)))
  )

  def decodeAll(
      allInsts: Seq[RVInst],
      inst: chisel3.UInt,
      specs: Seq[(DecodeField[RVInst, UInt], BitPat)]
  ): Seq[chisel3.UInt] =
    specs.map { case (field, default) =>
      val mapping = allInsts.map(p => (p.bitPat, field.genTable(p)))
      val table = TruthTable(mapping, default)
      decoder(QMCMinimizer, inst, table)
    }

}
