package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A Scala-side opcode for a hardware field: `lit` is the value the field carries.
  *
  * The DSL has no enum type, so an opcode set is a plain Scala 3 `enum` used as the compile-time constant table and the
  * field itself stays a `UInt`. Consumers therefore compare against literals instead of matching on a typed value; the
  * win is that decode, the FUs and any host-side tool all read one definition.
  */
trait Opcode:
  def lit: Int

object Opcode:
  /** Field width for an opcode set: the bits the largest literal needs. */
  def width(opcodes: IterableOnce[Opcode]): Int = log2Ceil(opcodes.iterator.map(_.lit).max + 1)

  /** Take one encoding out of a wider opcode field: consumers of a shared field only ever look at its low bits, which
    * is what lets several sets alias in the same bits (an instruction carries exactly one of them).
    */
  def take[LHS <: Referable[UInt]](u: LHS, width: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] =
    val bits: Node[Bits] = Node(u.asBits)
    bits.bits(width - 1, 0).asUInt

  /** Bits `[offset + width - 1 : offset]` of a shared field: the general form of [[take]], for the columns that sit
    * above each other inside one window (see [[OpColumn]]).
    */
  def slice[LHS <: Referable[UInt]](u: LHS, offset: Int, width: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] =
    require(offset >= 0 && width >= 1, s"slice expects offset >= 0 and width >= 1, got offset $offset width $width")
    if offset == 0 then take(u, width) else take(u >> offset, width)

/** A named column of a composed opcode window: where it sits, how wide it is, and how the Scala-side literal (decode)
  * and the hardware field (a backend) map into each other.
  *
  * Windows are low-anchored, so a column's offset is stable however wide the shared field grows, and two columns
  * compose with `|` because they never overlap.
  */
case class OpColumn(offset: Int, width: Int):
  require(offset >= 0 && width >= 1, s"OpColumn expects offset >= 0 and width >= 1, got offset $offset width $width")

  /** One past this column's last bit: the next column's offset, and the window length once this is the last column. */
  def end: Int = offset + width

  /** This column's contribution to a composed window literal (decode side). */
  def lit(op: Opcode): Int = op.lit << offset

  /** Read this column back out of a composed window (backend side). */
  def apply[LHS <: Referable[UInt]](u: LHS)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] = Opcode.slice(u, offset, width)
