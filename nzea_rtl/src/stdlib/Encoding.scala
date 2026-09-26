package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A field encoding: `lit` is the code a hardware field carries for one variant.
  *
  * The DSL has no enum type, so an encoding is a plain Scala 3 `enum` used as the compile-time constant table and the
  * field itself stays a `UInt`. Consumers therefore compare against literals instead of matching on a typed value; the
  * win is that decode, the execution units and any host-side tool all read one definition.
  */
trait Encoding:
  def lit: Int

object Encoding:
  /** Field width for an encoding: the bits the largest code needs. */
  def width(codes: IterableOnce[Encoding]): Int = log2Ceil(codes.iterator.map(_.lit).max + 1)

  /** Take one code out of a wider field: consumers of a shared field only ever look at its low bits, which is what lets
    * several encodings alias in the same bits (an instruction carries exactly one of them).
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
    * above each other inside one window (see [[Column]]).
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

  /** An encoding whose codes are one bit per variant, in declaration order: bit `i` names the `i`-th case.
    *
    * Deriving the code means it cannot drift from the declaration order, which is what a one-hot select depends on:
    * `mux1H` maps bit `i` to variant `i`, and [[Encoding.width]] reads the largest code. Reordering the cases therefore
    * changes the encoding — an internal encoding, not an ABI. Up to 31 variants (the code is an `Int`).
    */
  trait OneHot extends Encoding:
    self: scala.reflect.Enum =>
    final def lit: Int = 1 << ordinal

  /** An encoding whose codes are the declaration index: for sets that are compared and dispatched densely. */
  trait Sequential extends Encoding:
    self: scala.reflect.Enum =>
    final def lit: Int = ordinal

  /** A named column of a composed field: where it sits, how wide it is, and how the host-side code (the side that
    * composes a value) and the hardware (the side that reads it back) map into each other.
    *
    * Fields are low-anchored, so a column's offset is stable however wide the shared field grows, and two columns
    * compose with `|` because they never overlap.
    */
  case class Column(offset: Int, width: Int):
    require(offset >= 0 && width >= 1, s"Column expects offset >= 0 and width >= 1, got offset $offset width $width")

    /** One past this column's last bit: the next column's offset, and the field length once this is the last column. */
    def end: Int = offset + width

    /** This column's contribution to a composed field value (the composing side). */
    def lit(code: Encoding): Int = code.lit << offset

    /** Read this column back out of a composed field (the reading side). */
    def apply[LHS <: Referable[UInt]](u: LHS)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] = slice(u, offset, width)

/** The variant set of an [[Encoding.OneHot]], for code that reads the set whole instead of one code at a time.
  *
  * Carried by the enum's companion, where the compiler synthesizes `values` — the only place the declaration order is
  * reachable without a macro. A set whose codes must *not* follow the declaration order keeps writing `lit` by hand:
  * both traits are opt-in for exactly that reason.
  */
trait OneHotSet[E <: Encoding.OneHot & scala.reflect.Enum]:
  /** The variants in one-hot bit order: `variants(i)` is the variant bit `i` selects. */
  def variants: Seq[E]

  /** `Mux1H` over the set: `entry` supplies each variant's value, so no caller does bit math. */
  final def mux1H(sel: Referable[UInt], entry: E => Referable[UInt], width: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] =
    nzea_rtl.stdlib.mux1H(sel, variants.map(entry), width)
