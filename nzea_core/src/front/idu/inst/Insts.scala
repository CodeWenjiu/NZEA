package nzea_core.front.idu.inst

import me.jiuyang.decoder.BitSet
import nzea_core.back.int.{AluOp, BruOp, DivOp, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.{Backend, Isa, Srcs}
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

    /** Whether this dispatch is one the configuration can issue: the union must build the case, and the case must carry
      * a member its own set has — a case whose members come and go with the ISA is only as present as its payload.
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
      * configuration, which the int set carries; the mem window's code is its own op's, its single case carrying no
      * tag.
      */
    def window(ops: Encoding.UnionSet[IntOp, Isa]): Int = op match
        case DispatchOp.Int(intOp) => ops.lit(intOp)
        case DispatchOp.Mem(lsuOp) => MemOp.set(ops.config).lit(MemOp.Lsu(lsuOp))

    /** Whether this instruction can be issued under `isa` — the configuration's question, asked of the dispatch, so a
      * row whose unit (or whose unit's member) the configuration does not have never reaches the decode tables.
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
  * A row says nothing about whether it is in the table: which rows exist is the register width's answer ([[InstI.rows]]
  * and [[InstM.rows]]), and whether one can be issued is the configuration's ([[Dispatch.encodable]]).
  */
case class RVInst(
    name: String,
    bitPatStr: String,
    immType: Option[ImmType],
    dispatch: Dispatch,
    gprWr: Boolean = true
):

    /** The instruction pattern, without the Verilog-style `b` prefix the source carries to stay diffable with the
      * legacy decoder.
      */
    def pattern: BitSet = BitSet.bitset(bitPatStr.stripPrefix("b"))

/** The pattern shapes the table's rows are written with: the format's open fields as don't-care runs, its fixed fields
  * spelled out — the same masks the legacy decoder's patterns carried, so a row stays diffable field for field. The
  * four layouts cover the whole table; ECALL and EBREAK spell their single encodings out.
  */
private[idu] def n(bits: Int) = "?" * bits

/** Opcode only: the U-type and J-type patterns (LUI, AUIPC, JAL). */
private[idu] def uType(code: String) = "b" + n(25) + code

/** funct3 at `[14:12]`, everything else the format fixes open: the I-type patterns (JALR, the loads, the
  * register-immediate ALU ops, ADDIW) and every pattern whose immediate or register fields are all don't-cares (stores,
  * branches, FENCE, the CSR forms).
  */
private[idu] def iType(funct3: String, code: String) = "b" + n(7) + n(5) + n(5) + funct3 + n(5) + code

/** funct7 and funct3 fixed: the R-type patterns and the shift/word immediates (their `[31:25]` shares the field). */
private[idu] def rType(funct7: String, funct3: String, code: String) =
    "b" + funct7 + n(5) + n(5) + funct3 + n(5) + code

/** The 64-bit shift immediates: funct6 and a six-bit shift amount, bit 25 included. */
private[idu] def shift64(funct6: String, funct3: String) = "b" + funct6 + n(6) + n(5) + funct3 + n(5) + "0010011"

/** What decode minimises: the configured instruction table, for the register width the ISA carries. The rows are the
  * two ISA files' answer to that width.
  */
object RiscvInsts:

    /** All instructions for decode, in table order: the base ISA's rows then the M extension's — each selected by the
      * register width — minus the rows this configuration cannot issue. The two questions are separate: the width
      * decides which rows exist (the row files' `rows(xlen)` answers it, and only there), the configuration decides
      * which are issuable (the dispatch answers it, so a row whose unit the configuration does not have never reaches
      * the tables).
      */
    def all(isa: Isa): Seq[RVInst] =
        val rows = InstI.rows(isa.xlen) ++ InstM.rows(isa.xlen)
        rows.filter(inst => inst.dispatch.encodable(isa))
