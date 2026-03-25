package nzea_core.frontend

import chisel3._
import chisel3.util.{BitPat, Mux1H, MuxCase}
import chisel3.util.experimental.decode.{
  DecodeField,
  DecodePattern,
  TruthTable,
  QMCMinimizer,
  decoder
}
import nzea_core.backend.integer.FuOpWidth
import nzea_core.backend.integer.{AluOp, BruOp, DivOp, LsuOp, MulOp, SysuOp}
import nzea_config.NzeaConfig
// -------- Instruction pattern & decode fields (ImmType, Fu = op+src per FU, RVInst, RiscvInsts) --------

/** One-hot encoding for Mux1H; better timing than binary. */
object ImmType extends chisel3.ChiselEnum {
  val I = Value((1 << 0).U)
  val S = Value((1 << 1).U)
  val B = Value((1 << 2).U)
  val U = Value((1 << 3).U)
  val J = Value((1 << 4).U)
  val Z = Value((1 << 5).U)  // zero-extend inst[19:15] for CSR zimm
}

/** Function unit type for routing (ALU/BRU/LSU/MUL/DIV/SYSU). */
object FuType extends chisel3.ChiselEnum {
  val ALU  = Value
  val BRU  = Value
  val LSU  = Value
  val MUL  = Value
  val DIV  = Value
  val SYSU = Value
}

/** CSR type: None = no mapping (for blocking/bypass); others = machine-mode CSRs. */
object CsrType extends chisel3.ChiselEnum {
  val None     = Value
  val Mstatus  = Value
  val Mtvec    = Value
  val Mepc     = Value
  val Mcause   = Value
  val Mscratch = Value

  val MSTATUS_ADDR  = 0x300
  val MTVEC_ADDR    = 0x305
  val MSCRATCH_ADDR = 0x340
  val MEPC_ADDR     = 0x341
  val MCAUSE_ADDR   = 0x342

  def fromAddr(addr: UInt): CsrType.Type = {
    val a = addr(11, 0)
    MuxCase(CsrType.None, Seq(
      (a === MSTATUS_ADDR.U)  -> CsrType.Mstatus,
      (a === MTVEC_ADDR.U)    -> CsrType.Mtvec,
      (a === MSCRATCH_ADDR.U) -> CsrType.Mscratch,
      (a === MEPC_ADDR.U)     -> CsrType.Mepc,
      (a === MCAUSE_ADDR.U)   -> CsrType.Mcause
    ))
  }

  /** Convert CsrType to (imm, valid) for DPI commit_trace. imm = CSR addr (12-bit), valid = csr_type =/= None. */
  def toImmValid(csrType: CsrType.Type): (UInt, Bool) = {
    val valid = csrType =/= CsrType.None
    val imm = Mux1H(
      Seq(
        csrType === CsrType.Mstatus,
        csrType === CsrType.Mtvec,
        csrType === CsrType.Mepc,
        csrType === CsrType.Mcause,
        csrType === CsrType.Mscratch
      ),
      Seq(MSTATUS_ADDR.U, MTVEC_ADDR.U, MEPC_ADDR.U, MCAUSE_ADDR.U, MSCRATCH_ADDR.U)
    )
    (imm, valid)
  }
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

/** Single FU descriptor per instruction: op + src for that FU (no cross-FU
  * op/src). Decode yields fu_type, fu_op, fu_src.
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

/** fu_src width: max of all FU src enum widths; when adding/changing FUs,
  * update this Seq and the ChiselEnums only.
  */
object FuSrcWidth {
  val Width: Int = Seq(AluSrc.getWidth, BruSrc.getWidth, LsuSrc.getWidth).max
}

/** Slice unified fu_src/fu_op by enum width for each FU's .safe(); use
  * take(fu_*, X.getWidth) in ISU, no manual bit-width.
  */
object FuDecode {
  def take(u: UInt, width: Int): UInt = u(width - 1, 0)
}

/** RISC-V instruction pattern: single fu (op+src); decode yields fu_type,
  * fu_op, fu_src, gpr_wr (true if instruction writes GPR).
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

/** RV32I instruction set. Bit layout: [31:25] funct7, [24:20] rs2, [19:15] rs1,
  * [14:12] funct3, [11:7] rd, [6:0] opcode.
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

  /** All instructions for decode; M extension included only when isaConfig.hasM. */
  def all(implicit config: NzeaConfig): Seq[RVInst] = base ++ (if (config.isaConfig.hasM) m else Seq.empty)
}

// -------- DecodeField helpers --------

trait DecodeAPI {
  def bitPatFor[D <: chisel3.Data](v: D): BitPat = BitPat(
    v.litValue.U(v.getWidth.W)
  )
}

// -------- DecodeFields --------

/** Outputs UInt(width) so decode never casts to ImmType (avoids W001); use
  * ImmType.safe() when reading.
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

/** All decode fields with defaults; decode in one pass (one decoder call per
  * field).
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
