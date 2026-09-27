package nzea_core.front.idu

import me.jiuyang.decoder.BitSet
import nzea_core.CoreConfig
import nzea_core.back.int.{AluOp, BruOp, DivOp, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.{Backend, Isa, Srcs, Xlen}
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

  /** Whether this dispatch is one the configuration can issue: the union must build the case, and the case must carry a
    * member its own set has — a case whose members come and go with the ISA is only as present as its payload.
    */
  def exists(isa: Isa): Boolean = this match
    case Int(op) => IntOp.set(isa).contains(op) && op.exists(isa)
    case Mem(op) => LsuOp.set(isa).contains(op)

/** What decode answers for one instruction: the backend it goes to, the window that backend's columns were packed into,
  * the operand form its slots are resolved from, and a name for reports and decode-table encoding keys.
  *
  * Only the combinators below can build one, so a table entry never spells out a window layout and the name always
  * agrees with what is packed.
  */
case class Dispatch private (backend: Backend, op: DispatchOp, srcs: Srcs, name: String):

  /** This instruction's window value, packed against the units the configuration has. Both windows come from the same
    * configuration, which the int set carries; the mem window's code is its own op's, its single case carrying no tag.
    */
  def window(ops: Encoding.UnionSet[IntOp, Isa]): Int = op match
    case DispatchOp.Int(intOp) => ops.lit(intOp)
    case DispatchOp.Mem(lsuOp) => MemOp.set(ops.config).lit(MemOp.Lsu(lsuOp))

  /** Whether this instruction can be issued under `isa` — the row's own question, asked of the dispatch, so a row whose
    * unit (or whose unit's member) the configuration does not have never reaches the decode tables.
    */
  def encodable(isa: Isa): Boolean = op.exists(isa)

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
  *
  * A row only part of the ISA range has carries its own predicate ([exists]) — the re-encoded shift immediates are the
  * case in point, since both versions dispatch to the same op and only the pattern differs. Everything else about
  * whether a row is in the table is the dispatch's answer, not the row's.
  */
case class RVInst(
    name: String,
    bitPatStr: String,
    immType: Option[ImmType],
    dispatch: Dispatch,
    gprWr: Boolean = true,
    exists: Isa => Boolean = _ => true
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
    Dispatch.alu(AluOp.Add, Srcs.ImmZero)
  )

  val AUIPC = RVInst(
    "AUIPC",
    "b" + n(25) + "0010111",
    Some(ImmType.U),
    Dispatch.alu(AluOp.Add, Srcs.PcImm)
  )

  // J-type: JAL=1101111
  val JAL = RVInst(
    "JAL",
    "b" + n(25) + "1101111",
    Some(ImmType.J),
    Dispatch.bru(BruOp.JAL, Srcs.NoOperand)
  )

  // I-type
  val JALR = RVInst(
    "JALR",
    "b" + n(12) + n(5) + "000" + n(5) + "1100111",
    Some(ImmType.I),
    Dispatch.bru(BruOp.JALR, Srcs.Rs1Imm)
  )

  val LB = RVInst(
    "LB",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LB, Srcs.Rs1Imm)
  )

  val LH = RVInst(
    "LH",
    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LH, Srcs.Rs1Imm)
  )

  val LW = RVInst(
    "LW",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LW, Srcs.Rs1Imm)
  )

  val LBU = RVInst(
    "LBU",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LBU, Srcs.Rs1Imm)
  )

  val LHU = RVInst(
    "LHU",
    "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LHU, Srcs.Rs1Imm)
  )

  val ADDI = RVInst(
    "ADDI",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Add, Srcs.Rs1Imm)
  )

  val SLTI = RVInst(
    "SLTI",
    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Slt, Srcs.Rs1Imm)
  )

  val SLTIU = RVInst(
    "SLTIU",
    "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sltu, Srcs.Rs1Imm)
  )

  val XORI = RVInst(
    "XORI",
    "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Xor, Srcs.Rs1Imm)
  )

  val ORI = RVInst(
    "ORI",
    "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Or, Srcs.Rs1Imm)
  )

  val ANDI = RVInst(
    "ANDI",
    "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.And, Srcs.Rs1Imm)
  )

  // The shift immediates: a five-bit shift amount under funct7, bit 25 pinned to zero. RV64 re-encodes these three, which
  // is why they carry the names a wider ISA lands on.
  val SLLI = RVInst(
    "SLLI",
    "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sll, Srcs.Rs1Imm)
  )

  val SRLI = RVInst(
    "SRLI",
    "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Srl, Srcs.Rs1Imm)
  )

  val SRAI = RVInst(
    "SRAI",
    "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sra, Srcs.Rs1Imm)
  )

  val ECALL = RVInst(
    "ECALL",
    "b00000000000000000000000001110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.ECALL, Srcs.NoOperand),
    gprWr = false
  )

  val EBREAK = RVInst(
    "EBREAK",
    "b00000000000100000000000001110011",
    Some(ImmType.I),
    Dispatch.sysu(SysuOp.EBREAK, Srcs.NoOperand),
    gprWr = false
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
    Dispatch.sysu(SysuOp.CSRRWI, Srcs.ImmZero)
  )

  val CSRRSI = RVInst(
    "CSRRSI",
    "b" + n(12) + n(5) + "110" + n(5) + "1110011",
    Some(ImmType.Z),
    Dispatch.sysu(SysuOp.CSRRSI, Srcs.ImmZero)
  )

  val CSRRCI = RVInst(
    "CSRRCI",
    "b" + n(12) + n(5) + "111" + n(5) + "1110011",
    Some(ImmType.Z),
    Dispatch.sysu(SysuOp.CSRRCI, Srcs.ImmZero)
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
    gprWr = false
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

  // RV64: the 64-bit forms of the base ISA and of M. They are 32-bit instruction words like the rest, so the tables read
  // them the same way; what makes them RV64 is the register width they name, which is why only a 64-bit configuration
  // carries the rows.
  val LD = RVInst(
    "LD",
    "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LD, Srcs.Rs1Imm)
  )

  val LWU = RVInst(
    "LWU",
    "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0000011",
    Some(ImmType.I),
    Dispatch.mem(LsuOp.LWU, Srcs.Rs1Imm)
  )

  val SD = RVInst(
    "SD",
    "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0100011",
    Some(ImmType.S),
    Dispatch.mem(LsuOp.SD, Srcs.Rs1ImmRs2),
    gprWr = false
  )

  val ADDIW = RVInst(
    "ADDIW",
    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0011011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Addw, Srcs.Rs1Imm)
  )

  val SLLIW = RVInst(
    "SLLIW",
    "b0000000" + n(5) + n(5) + "001" + n(5) + "0011011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sllw, Srcs.Rs1Imm)
  )

  val SRLIW = RVInst(
    "SRLIW",
    "b0000000" + n(5) + n(5) + "101" + n(5) + "0011011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Srlw, Srcs.Rs1Imm)
  )

  val SRAIW = RVInst(
    "SRAIW",
    "b0100000" + n(5) + n(5) + "101" + n(5) + "0011011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sraw, Srcs.Rs1Imm)
  )

  val ADDW =
    RVInst("ADDW", "b0000000" + n(5) + n(5) + "000" + n(5) + "0111011", None, Dispatch.alu(AluOp.Addw, Srcs.Rs1Rs2))

  val SUBW =
    RVInst("SUBW", "b0100000" + n(5) + n(5) + "000" + n(5) + "0111011", None, Dispatch.alu(AluOp.Subw, Srcs.Rs1Rs2))

  val SLLW =
    RVInst("SLLW", "b0000000" + n(5) + n(5) + "001" + n(5) + "0111011", None, Dispatch.alu(AluOp.Sllw, Srcs.Rs1Rs2))

  val SRLW =
    RVInst("SRLW", "b0000000" + n(5) + n(5) + "101" + n(5) + "0111011", None, Dispatch.alu(AluOp.Srlw, Srcs.Rs1Rs2))

  val SRAW =
    RVInst("SRAW", "b0100000" + n(5) + n(5) + "101" + n(5) + "0111011", None, Dispatch.alu(AluOp.Sraw, Srcs.Rs1Rs2))

  val MULW = RVInst("MULW", "b0000001" + n(5) + n(5) + "000" + n(5) + "0111011", None, Dispatch.mul(MulOp.Mulw))

  val DIVW = RVInst("DIVW", "b0000001" + n(5) + n(5) + "100" + n(5) + "0111011", None, Dispatch.div(DivOp.Divw))

  val DIVUW = RVInst("DIVUW", "b0000001" + n(5) + n(5) + "101" + n(5) + "0111011", None, Dispatch.div(DivOp.Divuw))

  val REMW = RVInst("REMW", "b0000001" + n(5) + n(5) + "110" + n(5) + "0111011", None, Dispatch.div(DivOp.Remw))

  val REMUW = RVInst("REMUW", "b0000001" + n(5) + n(5) + "111" + n(5) + "0111011", None, Dispatch.div(DivOp.Remuw))

  // RV64 re-encodes the shift immediates: funct6 and a six-bit shift amount, bit 25 included. Same instruction names, so
  // they land on the rows above when the configuration keeps them (`exists`); without 64-bit registers the base rows
  // stand as they are.
  val SLLI64 = RVInst(
    "SLLI",
    "b000000" + n(6) + n(5) + "001" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sll, Srcs.Rs1Imm),
    exists = _.xlen == Xlen.Rv64
  )

  val SRLI64 = RVInst(
    "SRLI",
    "b000000" + n(6) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Srl, Srcs.Rs1Imm),
    exists = _.xlen == Xlen.Rv64
  )

  val SRAI64 = RVInst(
    "SRAI",
    "b010000" + n(6) + n(5) + "101" + n(5) + "0010011",
    Some(ImmType.I),
    Dispatch.alu(AluOp.Sra, Srcs.Rs1Imm),
    exists = _.xlen == Xlen.Rv64
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

  /** The M extension's word forms: RV64 only, like the rest of the W ops — the word members of [[MulOp]]/[[DivOp]]
    * answer for them, exactly as they answer for the multiplier and divider themselves.
    */
  private val mw: Seq[RVInst] = Seq(MULW, DIVW, DIVUW, REMW, REMUW)

  /** The 64-bit forms of the base ISA: the instructions it adds, plus the three shift immediates it re-encodes under
    * the same names.
    */
  private val rv64: Seq[RVInst] =
    Seq(LD, LWU, SD, ADDIW, SLLIW, SRLIW, SRAIW, ADDW, SUBW, SLLW, SRLW, SRAW, SLLI64, SRLI64, SRAI64)

  /** Later rows land on earlier ones: a row whose instruction name already appears replaces it where it stands, so a
    * wider ISA can re-encode a base instruction without the row list branching on the register width. A row that lands
    * on nothing is appended, which lets one list carry both the new instructions and the re-encodings. The overrides
    * arrive already filtered by their own predicate, so an absent one leaves the base row it would have replaced.
    */
  private def over(base: Seq[RVInst], overrides: Seq[RVInst]): Seq[RVInst] =
    val replacing = overrides.map(i => i.name -> i).toMap
    base.map(i => replacing.getOrElse(i.name, i)) ++ overrides.filterNot(o => base.exists(_.name == o.name))

  /** All instructions for decode, in table order: every declared row, minus the ones this configuration does not have.
    * Which rows exist is a fact each row carries ([[RVInst.exists]] — the 64-bit re-encodings, which are not an op
    * fact), and whether a row can be issued is the dispatch's own answer ([[Dispatch.encodable]]), so no row restates a
    * configuration fact twice and nothing here branches on the register width.
    */
  def all(config: CoreConfig): Seq[RVInst] =
    val isa = config.isa
    val rows = over(base, rv64.filter(_.exists(isa))) ++ m ++ mw
    rows.filter(inst => inst.exists(isa) && inst.dispatch.encodable(isa))
