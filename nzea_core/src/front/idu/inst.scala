package nzea_core.front.idu

import me.jiuyang.decoder.BitSet
import nzea_core.back.int.{AluOp, BruOp, DivOp, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.{Backend, Srcs}
import nzea_config.core.CoreConfig
import nzea_rtl.stdlib.*

/** Immediate shape, one-hot so the decode stage picks an immediate with `mux1H` and gets the better timing of a one-hot
  * select. `Z` zero-extends `inst[19:15]` for the CSR immediate forms.
  */
enum ImmType extends Encoding.OneHot:
  case I
  case S
  case B
  case U
  case J
  case Z

object ImmType:
  val width: Int = Encoding.width(values)

/** The op a decode result writes into the shared `op` field: an int unit's case, or the memory path's single one. It is
  * kept symbolic — the value is packed against the units the configuration has when the decode tables are built.
  */
enum DispatchOp:
  case Int(op: IntOp)
  case Mem(op: LsuOp)

/** What decode answers for one instruction: the backend it goes to, the window that backend's columns were packed into,
  * the operand form its slots are resolved from, and a name for reports and decode-table encoding keys.
  *
  * Only the combinators below can build one, so a table entry never spells out a window layout and the name always
  * agrees with what is packed.
  */
case class Dispatch private (backend: Backend, op: DispatchOp, srcs: Srcs, name: String):

  /** This instruction's window value, packed against the units the configuration has. */
  def window(ops: Encoding.UnionSet[IntOp]): Int = op match
    case DispatchOp.Int(intOp) => ops.lit(intOp)
    case DispatchOp.Mem(lsuOp) => MemOp.lit(MemOp.Lsu(lsuOp))

object Dispatch:
  def alu(op: AluOp, srcs: Srcs): Dispatch = int(IntOp.Alu(op), srcs)
  def bru(op: BruOp, srcs: Srcs): Dispatch = int(IntOp.Bru(op), srcs)

  /** MUL and DIV read both register sources, so their operand form is fixed. */
  def mul(op: MulOp): Dispatch = int(IntOp.Mul(op), Srcs.Rs1Rs2)
  def div(op: DivOp): Dispatch = int(IntOp.Div(op), Srcs.Rs1Rs2)

  def sysu(op: SysuOp, srcs: Srcs): Dispatch = int(IntOp.Sysu(op), srcs)

  def mem(op: LsuOp, srcs: Srcs): Dispatch =
    Dispatch(Backend.MEM, DispatchOp.Mem(op), srcs, s"mem_${op}_$srcs")

  private def int(op: IntOp, srcs: Srcs): Dispatch =
    Dispatch(Backend.INT, DispatchOp.Int(op), srcs, s"int_${op}_$srcs")

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
    Dispatch.alu(AluOp.Add, Srcs.ImmZero),
    rs1Rd = false,
    rs2Rd = false
  )

  val AUIPC = RVInst(
    "AUIPC",
    "b" + n(25) + "0010111",
    Some(ImmType.U),
    Dispatch.alu(AluOp.Add, Srcs.PcImm),
    rs1Rd = false,
    rs2Rd = false
  )

  // J-type: JAL=1101111
  val JAL = RVInst(
    "JAL",
    "b" + n(25) + "1101111",
    Some(ImmType.J),
    Dispatch.bru(BruOp.JAL, Srcs.NoOperand),
    rs1Rd = false,
    rs2Rd = false
  )

  // I-type
  val JALR = RVInst(
    "JALR",
    "b" + n(12) + n(5) + "000" + n(5) + "1100111",
    Some(ImmType.I),
    Dispatch.bru(BruOp.JALR, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val LB = RVInst(
    "LB",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LB, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val LH = RVInst(
    "LH",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LH, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val LW = RVInst(
    "LW",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LW, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val LBU = RVInst(
    "LBU",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LBU, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val LHU = RVInst(
    "LHU",
    "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LHU, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val ADDI = RVInst(
    "ADDI",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Add, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val SLTI = RVInst(
    "SLTI",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Slt, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val SLTIU = RVInst(
    "SLTIU",
    "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sltu, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val XORI = RVInst(
    "XORI",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Xor, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val ORI = RVInst(
    "ORI",
    "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Or, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val ANDI = RVInst(
    "ANDI",
    "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.And, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val SLLI = RVInst(
    "SLLI",
    "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sll, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val SRLI = RVInst(
    "SRLI",
    "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Srl, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val SRAI = RVInst(
    "SRAI",
    "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sra, Srcs.Rs1Imm),
    rs2Rd = false
  )

  val ECALL = RVInst(
    "ECALL",
    "b00000000000000000000000001110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.ECALL, Srcs.NoOperand),
    gprWr = false,
    rs1Rd = false,
    rs2Rd = false
  )

  val EBREAK = RVInst(
    "EBREAK",
    "b00000000000100000000000001110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.EBREAK, Srcs.NoOperand),
    gprWr = false,
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRW = RVInst(
    "CSRRW",
    "b" + n(12) + n(5) + "001" + n(5) + "1110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.CSRRW, Srcs.Rs1)
  )

  val CSRRS = RVInst(
    "CSRRS",
    "b" + n(12) + n(5) + "010" + n(5) + "1110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.CSRRS, Srcs.Rs1)
  )

  val CSRRC = RVInst(
    "CSRRC",
    "b" + n(12) + n(5) + "011" + n(5) + "1110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.CSRRC, Srcs.Rs1)
  )

  val CSRRWI = RVInst(
    "CSRRWI",
    "b" + n(12) + n(5) + "101" + n(5) + "1110011",
    Some(ImmType.Z),
    Dispatch.sysu(SysuOp.CSRRWI, Srcs.ImmZero),
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRSI = RVInst(
    "CSRRSI",
    "b" + n(12) + n(5) + "110" + n(5) + "1110011",
    Some(ImmType.Z),
    Dispatch.sysu(SysuOp.CSRRSI, Srcs.ImmZero),
    rs1Rd = false,
    rs2Rd = false
  )

  val CSRRCI = RVInst(
    "CSRRCI",
    "b" + n(12) + n(5) + "111" + n(5) + "1110011",
    Some(ImmType.Z),
    Dispatch.sysu(SysuOp.CSRRCI, Srcs.ImmZero),
    rs1Rd = false,
    rs2Rd = false
  )

  // S-type: rs1=base, rs2=data; no rd
  val SB = RVInst(
    "SB",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0100011",
    Some(ImmType.S),
    Dispatch.mem(LsuOp.SB, Srcs.Rs1ImmRs2),
    gprWr = false
  )

  val SH = RVInst(
    "SH",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0100011",
    Some(ImmType.S),
    Dispatch.mem(LsuOp.SH, Srcs.Rs1ImmRs2),
    gprWr = false
  )

  val SW = RVInst(
    "SW",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0100011",
    Some(ImmType.S),
    Dispatch.mem(LsuOp.SW, Srcs.Rs1ImmRs2),
    gprWr = false
  )

  // B-type: rs1, rs2 for compare; no rd
  val BEQ = RVInst(
    "BEQ",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BEQ, Srcs.Rs1Rs2),
    gprWr = false
  )

  val BNE = RVInst(
    "BNE",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BNE, Srcs.Rs1Rs2),
    gprWr = false
  )

  val BLT = RVInst(
    "BLT",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BLT, Srcs.Rs1Rs2),
    gprWr = false
  )

  val BGE = RVInst(
    "BGE",
    "b" + n(7) + n(5) + n(5) + "101" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BGE, Srcs.Rs1Rs2),
    gprWr = false
  )

  val BLTU = RVInst(
    "BLTU",
    "b" + n(7) + n(5) + n(5) + "110" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BLTU, Srcs.Rs1Rs2),
    gprWr = false
  )

  val BGEU = RVInst(
    "BGEU",
    "b" + n(7) + n(5) + n(5) + "111" + n(5) + "1100011",
    Some(ImmType.B),
    Dispatch.bru(BruOp.BGEU, Srcs.Rs1Rs2),
    gprWr = false
  )

  // R-type: rs1, rs2
  val ADD =
    RVInst("ADD", "b0000000" + n(5) + n(5) + "000" + n(5) + "0110011", None, Dispatch.alu(AluOp.Add, Srcs.Rs1Rs2))

  val SUB =
    RVInst("SUB", "b0100000" + n(5) + n(5) + "000" + n(5) + "0110011", None, Dispatch.alu(AluOp.Sub, Srcs.Rs1Rs2))

  val SLL =
    RVInst("SLL", "b0000000" + n(5) + n(5) + "001" + n(5) + "0110011", None, Dispatch.alu(AluOp.Sll, Srcs.Rs1Rs2))

  val SLT =
    RVInst("SLT", "b0000000" + n(5) + n(5) + "010" + n(5) + "0110011", None, Dispatch.alu(AluOp.Slt, Srcs.Rs1Rs2))

  val SLTU =
    RVInst("SLTU", "b0000000" + n(5) + n(5) + "011" + n(5) + "0110011", None, Dispatch.alu(AluOp.Sltu, Srcs.Rs1Rs2))

  val XOR =
    RVInst("XOR", "b0000000" + n(5) + n(5) + "100" + n(5) + "0110011", None, Dispatch.alu(AluOp.Xor, Srcs.Rs1Rs2))

  val SRL =
    RVInst("SRL", "b0000000" + n(5) + n(5) + "101" + n(5) + "0110011", None, Dispatch.alu(AluOp.Srl, Srcs.Rs1Rs2))

  val SRA =
    RVInst("SRA", "b0100000" + n(5) + n(5) + "101" + n(5) + "0110011", None, Dispatch.alu(AluOp.Sra, Srcs.Rs1Rs2))

  val OR =
    RVInst("OR", "b0000000" + n(5) + n(5) + "110" + n(5) + "0110011", None, Dispatch.alu(AluOp.Or, Srcs.Rs1Rs2))

  val AND =
    RVInst("AND", "b0000000" + n(5) + n(5) + "111" + n(5) + "0110011", None, Dispatch.alu(AluOp.And, Srcs.Rs1Rs2))

  val FENCE = RVInst(
    "FENCE",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0001111",
    None,
    Dispatch.sysu(SysuOp.FENCE, Srcs.NoOperand),
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
