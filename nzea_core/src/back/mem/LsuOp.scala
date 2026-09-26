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
enum LsuOp(val lit: Int) extends Opcode:
  case LB extends LsuOp(1 << 0)
  case LH extends LsuOp(1 << 1)
  case LW extends LsuOp(1 << 2)
  case LBU extends LsuOp(1 << 3)
  case LHU extends LsuOp(1 << 4)
  case SB extends LsuOp(1 << 5)
  case SH extends LsuOp(1 << 6)
  case SW extends LsuOp(1 << 7)

object LsuOp:
  val width: Int = Opcode.width(values)

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

/** Memory address source: one-hot (Rs1Imm). */
enum LsuSrc(val lit: Int) extends Opcode:
  case Rs1Imm extends LsuSrc(1 << 0)

object LsuSrc:
  val width: Int = Opcode.width(values)

/** The mem path's window in the issue uop's shared `op` field, low-anchored: its op, then its address source.
  *
  * This backend has a single unit, so there is no unit select above the op — but the window is still its own: decode
  * composes it with [[MemOp.lit]], this backend reads the columns back with the columns themselves, and the shared
  * field is sized from [[MemOp.width]].
  */
object MemOp:
  val op = OpColumn(0, LsuOp.width)
  val src = OpColumn(op.end, LsuSrc.width)

  /** Window length: the bits this backend needs the shared field to have. */
  val width: Int = src.end

  /** Decode side: one mem instruction's window value. */
  def lit(op: LsuOp, src: LsuSrc): Int = MemOp.op.lit(op) | MemOp.src.lit(src)
