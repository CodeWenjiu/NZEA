package nzea_core.back.mem

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.{Isa, Xlen}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Memory op: one-hot (LB, LH, LW, LBU, LHU, SB, SH, SW), then RV64's LD, SD and LWU.
  *
  * The memory path needs no unit select on top of this: it has a single unit. The RV64 forms are declared last, so the
  * RV32 codes do not move when the configuration does not have them.
  */
enum LsuOp extends Encoding.OneHot:
    case LB
    case LH
    case LW
    case LBU
    case LHU
    case SB
    case SH
    case SW
    case LD
    case SD
    case LWU

    def exists(isa: Isa): Boolean = this match
        case LB | LH | LW | LBU | LHU | SB | SH | SW => true
        case LD | SD | LWU                           => isa.xlen == Xlen.Rv64

object LsuOp:
    /** The ops this configuration has: the word-wide forms need 64-bit registers. */
    def set(isa: Isa): OneHotSet[LsuOp] = OneHotSet.of(values.toSeq)(_.exists(isa))

    /** True for the store ops; a field that selects no store is treated as a load, like the legacy decoder. */
    def isStore(v: LsuOp): Boolean = v match
        case SB | SH | SW | SD => true
        case _                 => false

    /** The hardware test: whether the field carries one of the stores this configuration has. */
    def isStore[LHS <: Referable[UInt]](op: LHS, ops: OneHotSet[LsuOp])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[Bool] =
        ops.variants.filter(isStore).map(v => op === v.lit.U(ops.width)).reduce(_ | _)

/** The mem path's window in the issue uop's shared `op` field: one unit, so the union has a single case and no tag.
  *
  * It is a union all the same, so both backends name and derive their window the same way; with one case the payload
  * column is the whole window, which is the load/store op's own field. The address base, the offset and the store data
  * arrive in the uop's operand slots.
  */
enum MemOp extends Encoding.Union[Isa]:
    case Lsu(payload: LsuOp) extends MemOp

    def childWidth(isa: Isa): Int = LsuOp.set(isa).width

object MemOp:
    val lsu = Lsu(LsuOp.LB)

    def set(isa: Isa): Encoding.UnionSet[MemOp, Isa] = Encoding.UnionSet.of(isa)(Seq(lsu))(_ => true)
