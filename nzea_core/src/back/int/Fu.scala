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

/** ALU operand source for opA/opB; one-hot (Rs1Rs2, Rs1Imm, ImmZero, PcImm). */
enum AluSrc(val lit: Int) extends Opcode:
  case Rs1Rs2 extends AluSrc(1 << 0)
  case Rs1Imm extends AluSrc(1 << 1)
  case ImmZero extends AluSrc(1 << 2)
  case PcImm extends AluSrc(1 << 3)

object AluSrc:
  val width: Int = Opcode.width(values)

/** BRU target source: one-hot (PcImm, Rs1Imm). */
enum BruSrc(val lit: Int) extends Opcode:
  case PcImm extends BruSrc(1 << 0)
  case Rs1Imm extends BruSrc(1 << 1)

object BruSrc:
  val width: Int = Opcode.width(values)

/** This backend's source field: wide enough for every source encoding its FUs accept. */
object FuSrcWidth:
  val Width: Int = Seq(AluSrc.width, BruSrc.width).max

/** This backend's op field: wide enough for every op encoding its FUs accept. */
object FuOpWidth:
  val Width: Int = Seq(AluOp.width, BruOp.width, MulOp.width, DivOp.width, SysuOp.width).max

/** The int path's window in the issue uop's shared `op` field, low-anchored: the FU's own encoding, then the unit
  * select, then the operand source.
  *
  * The whole decode result of an int instruction lives here and nowhere else knows the layout: decode composes it with
  * [[IntOp.lit]], this backend reads the columns back with the columns themselves, and the shared field is sized from
  * [[IntOp.width]].
  */
object IntOp:
  /** The FU's own op, which the FUs dispatch over with `mux1H` straight from this slice. */
  val fuOp = OpColumn(0, FuOpWidth.Width)

  /** Which int FU takes the uop. */
  val fuType = OpColumn(fuOp.end, FuType.width)

  /** Which operands feed that FU. */
  val fuSrc = OpColumn(fuType.end, FuSrcWidth.Width)

  /** Window length: the bits this backend needs the shared field to have. */
  val width: Int = fuSrc.end

  /** Decode side: one int instruction's window value. */
  def lit(unit: FuType, op: Opcode, src: Opcode): Int = fuOp.lit(op) | fuType.lit(unit) | fuSrc.lit(src)

  /** Units that take no operand source (MUL/DIV/SYSU): the source column stays zero on purpose, like the legacy
    * decoder, and the FU never looks at it.
    */
  def lit(unit: FuType, op: Opcode): Int = fuOp.lit(op) | fuType.lit(unit)
