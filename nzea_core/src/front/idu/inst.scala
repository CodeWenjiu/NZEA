package nzea_core.front.idu

import me.jiuyang.decoder.BitSet
import nzea_core.back.int.{AluOp, AluSrc, BruOp, BruSrc, DivOp, FuType, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, LsuSrc, MemOp}
import nzea_core.isa.Backend
import nzea_config.core.CoreConfig
import nzea_rtl.stdlib.*

/** Immediate shape, one-hot so the decode stage picks an immediate with `mux1H` and gets the better timing of a one-hot
  * select. `Z` zero-extends `inst[19:15]` for the CSR immediate forms.
  */
enum ImmType(val lit: Int) extends Opcode:
  case I extends ImmType(1 << 0)
  case S extends ImmType(1 << 1)
  case B extends ImmType(1 << 2)
  case U extends ImmType(1 << 3)
  case J extends ImmType(1 << 4)
  case Z extends ImmType(1 << 5)

object ImmType:
  val width: Int = Opcode.width(values)

/** What decode answers for one instruction: the backend it goes to, the window that backend's columns were packed into,
  * and a name for reports and decode-table encoding keys.
  *
  * Only the combinators below can build one, so a table entry never spells out a window layout and the name always
  * agrees with what is packed.
  */
case class Dispatch private (backend: Backend, window: Int, name: String)

object Dispatch:
  def alu(op: AluOp, src: AluSrc): Dispatch = int(FuType.ALU, op, src)
  def bru(op: BruOp, src: BruSrc): Dispatch = int(FuType.BRU, op, src)
  def mul(op: MulOp): Dispatch = int(FuType.MUL, op)
  def div(op: DivOp): Dispatch = int(FuType.DIV, op)
  def sysu(op: SysuOp): Dispatch = int(FuType.SYSU, op)
  def mem(op: LsuOp, src: LsuSrc): Dispatch = Dispatch(Backend.MEM, MemOp.lit(op, src), s"mem_${op}_$src")

  /** MUL/DIV/SYSU take no operand source: the window packs without one, leaving that column zero. */
  private def int(unit: FuType, op: Opcode, src: Opcode): Dispatch =
    Dispatch(Backend.INT, IntOp.lit(unit, op, src), s"int_${unit}_${op}_$src")

  private def int(unit: FuType, op: Opcode): Dispatch =
    Dispatch(Backend.INT, IntOp.lit(unit, op), s"int_${unit}_$op")

/** One RISC-V instruction pattern: the immediate shape it needs (`None` = it has no immediate, so the decode table
  * leaves that field to its default) and what decode hands the backend.
  *
  * RV32I bit layout: `[31:25]` funct7, `[24:20]` rs2, `[19:15]` rs1, `[14:12]` funct3, `[11:7]` rd, `[6:0]` opcode.
  */
case class RVInst(
    name: String,
    bitPatStr: String,
    immType: Option[ImmType],
    dispatch: Dispatch,
    gprWr: Boolean = true,
    rs1Rd: Boolean = true,
    rs2Rd: Boolean = true
):

  /** The instruction pattern, without the Verilog-style `b` prefix the source carries to stay diffable with the legacy
    * decoder.
    */
  def pattern: BitSet = BitSet.bitset(bitPatStr.stripPrefix("b"))

/** RV32I plus the M extension: the legacy decoder's patterns, verbatim. */
object RiscvInsts:
  private def n(bits: Int) = "?" * bits

  // U-type: LUI=0110111, AUIPC=0010111
  val LUI = RVInst(
    "LUI",
    "b" + n(25) + "0110111",
    Some(ImmType.U),
    Dispatch.alu(AluOp.Add, AluSrc.ImmZero),
    rs1Rd = false,
    rs2Rd = false
  )

  val AUIPC = RVInst(
    "AUIPC",
    "b" + n(25) + "0010111",
    Some(ImmType.U),
    Dispatch.alu(AluOp.Add, AluSrc.PcImm),
    rs1Rd = false,
    rs2Rd = false
  )

  // J-type: JAL=1101111
  val JAL = RVInst(
    "JAL",
    "b" + n(25) + "1101111",
    Some(ImmType.J),
    Dispatch.bru(BruOp.JAL, BruSrc.PcImm),
    rs1Rd = false,
    rs2Rd = false
  )

  // I-type
  val JALR = RVInst(
    "JALR",
    "b" + n(12) + n(5) + "000" + n(5) + "1100111",
    Some(ImmType.I),
    Dispatch.bru(BruOp.JALR, BruSrc.Rs1Imm),
    rs2Rd = false
  )

  val LB = RVInst(
    "LB",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LB, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val LH = RVInst(
    "LH",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LH, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val LW = RVInst(
    "LW",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LW, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val LBU = RVInst(
    "LBU",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LBU, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val LHU = RVInst(
    "LHU",
    "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LHU, LsuSrc.Rs1Imm),
    rs2Rd = false
  )

  val ADDI = RVInst(
    "ADDI",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Add, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SLTI = RVInst(
    "SLTI",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Slt, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SLTIU = RVInst(
    "SLTIU",
    "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sltu, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val XORI = RVInst(
    "XORI",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Xor, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val ORI = RVInst(
    "ORI",
    "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Or, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val ANDI = RVInst(
    "ANDI",
    "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.And, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SLLI = RVInst(
    "SLLI",
    "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sll, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SRLI = RVInst(
    "SRLI",
    "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Srl, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val SRAI = RVInst(
    "SRAI",
    "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sra, AluSrc.Rs1Imm),
    rs2Rd = false
  )

  val ECALL = RVInst(
    "ECALL",
    "b00000000000000000000000001110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.ECALL),
    gprWr = false,
    rs1Rd = false,
    rs2Rd = false
  )

  val EBREAK = RVInst(
    "EBREAK",
    "b00000000000100000000000001110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.EBREAK),
    gprWr = false,
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRW = RVInst(
    "CSRRW",
    "b" + n(12) + n(5) + "001" + n(5) + "1110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.CSRRW)
  )

  val CSRRS = RVInst(
    "CSRRS",
    "b" + n(12) + n(5) + "010" + n(5) + "1110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.CSRRS)
  )

  val CSRRC = RVInst(
    "CSRRC",
    "b" + n(12) + n(5) + "011" + n(5) + "1110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.CSRRC)
  )

  val CSRRWI = RVInst(
    "CSRRWI",
    "b" + n(12) + n(5) + "101" + n(5) + "1110011",
    Some(ImmType.Z),
    Dispatch.sysu(SysuOp.CSRRWI),
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRSI = RVInst(
    "CSRRSI",
    "b" + n(12) + n(5) + "110" + n(5) + "1110011",
    Some(ImmType.Z),
    Dispatch.sysu(SysuOp.CSRRSI),
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRCI = RVInst(
    "CSRRCI",
    "b" + n(12) + n(5) + "111" + n(5) + "1110011",
    Some(ImmType.Z),
    Dispatch.sysu(SysuOp.CSRRCI),
    rs1Rd = false,
    rs2Rd = false
  )

  // S-type: rs1=base, rs2=data; no rd
  val SB = RVInst(
    "SB",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0100011",
    Some(ImmType.S),
    Dispatch.mem(LsuOp.SB, LsuSrc.Rs1Imm),
    gprWr = false
  )

  val SH = RVInst(
    "SH",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0100011",
    Some(ImmType.S),
    Dispatch.mem(LsuOp.SH, LsuSrc.Rs1Imm),
    gprWr = false
  )

  val SW = RVInst(
    "SW",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0100011",
    Some(ImmType.S),
    Dispatch.mem(LsuOp.SW, LsuSrc.Rs1Imm),
    gprWr = false
  )

  // B-type: rs1, rs2 for compare; no rd
  val BEQ = RVInst(
    "BEQ",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BEQ, BruSrc.PcImm),
    gprWr = false
  )

  val BNE = RVInst(
    "BNE",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BNE, BruSrc.PcImm),
    gprWr = false
  )

  val BLT = RVInst(
    "BLT",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BLT, BruSrc.PcImm),
    gprWr = false
  )

  val BGE = RVInst(
    "BGE",
    "b" + n(7) + n(5) + n(5) + "101" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BGE, BruSrc.PcImm),
    gprWr = false
  )

  val BLTU = RVInst(
    "BLTU",
    "b" + n(7) + n(5) + n(5) + "110" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BLTU, BruSrc.PcImm),
    gprWr = false
  )

  val BGEU = RVInst(
    "BGEU",
    "b" + n(7) + n(5) + n(5) + "111" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BGEU, BruSrc.PcImm),
    gprWr = false
  )

  // R-type: rs1, rs2
  val ADD =
    RVInst("ADD", "b0000000" + n(5) + n(5) + "000" + n(5) + "0110011", None, Dispatch.alu(AluOp.Add, AluSrc.Rs1Rs2))

  val SUB =
    RVInst("SUB", "b0100000" + n(5) + n(5) + "000" + n(5) + "0110011", None, Dispatch.alu(AluOp.Sub, AluSrc.Rs1Rs2))

  val SLL =
    RVInst("SLL", "b0000000" + n(5) + n(5) + "001" + n(5) + "0110011", None, Dispatch.alu(AluOp.Sll, AluSrc.Rs1Rs2))

  val SLT =
    RVInst("SLT", "b0000000" + n(5) + n(5) + "010" + n(5) + "0110011", None, Dispatch.alu(AluOp.Slt, AluSrc.Rs1Rs2))

  val SLTU =
    RVInst("SLTU", "b0000000" + n(5) + n(5) + "011" + n(5) + "0110011", None, Dispatch.alu(AluOp.Sltu, AluSrc.Rs1Rs2))

  val XOR =
    RVInst("XOR", "b0000000" + n(5) + n(5) + "100" + n(5) + "0110011", None, Dispatch.alu(AluOp.Xor, AluSrc.Rs1Rs2))

  val SRL =
    RVInst("SRL", "b0000000" + n(5) + n(5) + "101" + n(5) + "0110011", None, Dispatch.alu(AluOp.Srl, AluSrc.Rs1Rs2))

  val SRA =
    RVInst("SRA", "b0100000" + n(5) + n(5) + "101" + n(5) + "0110011", None, Dispatch.alu(AluOp.Sra, AluSrc.Rs1Rs2))

  val OR =
    RVInst("OR", "b0000000" + n(5) + n(5) + "110" + n(5) + "0110011", None, Dispatch.alu(AluOp.Or, AluSrc.Rs1Rs2))

  val AND =
    RVInst("AND", "b0000000" + n(5) + n(5) + "111" + n(5) + "0110011", None, Dispatch.alu(AluOp.And, AluSrc.Rs1Rs2))

  val FENCE = RVInst(
    "FENCE",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0001111",
    None,
    Dispatch.sysu(SysuOp.FENCE),
    gprWr = false,
    rs1Rd = false,
    rs2Rd = false
  )

  // M extension: funct7=0000001
  val MUL = RVInst("MUL", "b0000001" + n(5) + n(5) + "000" + n(5) + "0110011", None, Dispatch.mul(MulOp.Mul))

  val MULH = RVInst("MULH", "b0000001" + n(5) + n(5) + "001" + n(5) + "0110011", None, Dispatch.mul(MulOp.Mulh))

  val MULHSU = RVInst("MULHSU", "b0000001" + n(5) + n(5) + "010" + n(5) + "0110011", None, Dispatch.mul(MulOp.Mulhsu))

  val MULHU = RVInst("MULHU", "b0000001" + n(5) + n(5) + "011" + n(5) + "0110011", None, Dispatch.mul(MulOp.Mulhu))

  val DIV = RVInst("DIV", "b0000001" + n(5) + n(5) + "100" + n(5) + "0110011", None, Dispatch.div(DivOp.Div))

  val DIVU = RVInst("DIVU", "b0000001" + n(5) + n(5) + "101" + n(5) + "0110011", None, Dispatch.div(DivOp.Divu))

  val REM = RVInst("REM", "b0000001" + n(5) + n(5) + "110" + n(5) + "0110011", None, Dispatch.div(DivOp.Rem))

  val REMU = RVInst("REMU", "b0000001" + n(5) + n(5) + "111" + n(5) + "0110011", None, Dispatch.div(DivOp.Remu))

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

  /** All instructions for decode; M is gated like every other optional extension. */
  def all(config: CoreConfig): Seq[RVInst] =
    require(
      config.width == 32,
      s"the decode tables describe a 32-bit instruction word, but the ISA config is ${config.width} bits"
    )
    base ++ (if config.isaConfig.hasM then m else Seq.empty)
