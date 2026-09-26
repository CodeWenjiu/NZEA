package nzea_core.back.int

import nzea_rtl.stdlib.*

/** Unit select inside the integer path (ALU/BRU/MUL/DIV/SYSU).
  *
  * Internal to this backend: decode dispatches to `nzea_core.isa.Backend` first, then this field says which int FU
  * takes the uop. The memory path needs no equivalent — it has a single unit.
  */
enum FuType(val lit: Int) extends Opcode:
  case ALU extends FuType(0)
  case BRU extends FuType(1)
  case MUL extends FuType(2)
  case DIV extends FuType(3)
  case SYSU extends FuType(4)

object FuType:
  val width: Int = Opcode.width(values)

/** This backend's op field: wide enough for every op encoding its FUs accept. */
object FuOpWidth:
  val Width: Int = Seq(AluOp.width, BruOp.width, MulOp.width, DivOp.width, SysuOp.width).max

/** The int path's window in the issue uop's shared `op` field, low-anchored: the FU's own op, then the unit select.
  *
  * The whole decode result of an int instruction lives here and nowhere else knows the layout: decode composes it with
  * [[IntOp.lit]], this backend reads the columns back with the columns themselves, and the shared field is sized from
  * [[IntOp.width]]. Operands are not part of this window — the uop's slots carry them already resolved, and which slot a
  * unit reads is this backend's own wiring.
  */
object IntOp:
  /** The FU's own op, which the FUs dispatch over with `mux1H` straight from this slice. */
  val fuOp = OpColumn(0, FuOpWidth.Width)

  /** Which int FU takes the uop. */
  val fuType = OpColumn(fuOp.end, FuType.width)

  /** Window length: the bits this backend needs the shared field to have. */
  val width: Int = fuType.end

  /** Decode side: one int instruction's window value. */
  def lit(unit: FuType, op: Opcode): Int = fuOp.lit(op) | fuType.lit(unit)
