package nzea_core.back.mem

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Memory op: one-hot (LB, LH, LW, LBU, LHU, SB, SH, SW); used by decode, the mem unit and the bus user payload.
  *
  * The memory path needs no unit select on top of this: it has a single unit.
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

object LsuOp:
  val width: Int = Encoding.width(values)

  /** True for the store ops; a field that selects no store is treated as a load, like the legacy decoder. */
  def isStore[LHS <: Referable[UInt]](op: LHS)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bool] =
    (op === LsuOp.SB.lit.U(width)) | (op === LsuOp.SH.lit.U(width)) | (op === LsuOp.SW.lit.U(width))

  def isLoad[LHS <: Referable[UInt]](op: LHS)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bool] = !isStore(op)

/** The mem path's window in the issue uop's shared `op` field: one unit, so the union has a single case and no tag.
  *
  * It is a union all the same, so both backends name and derive their window the same way; with one case the payload
  * column is the whole window, which is the load/store op's own field. The address base, the offset and the store data
  * arrive in the uop's operand slots.
  */
enum MemOp(val childWidth: Int) extends Encoding.Union:
  case Lsu(payload: LsuOp) extends MemOp(LsuOp.width)

object MemOp extends Encoding.UnionSet[MemOp]:
  val cases: Seq[MemOp] = Seq(Lsu(LsuOp.LB))
