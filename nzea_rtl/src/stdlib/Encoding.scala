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

  /** Bits `[offset + width - 1 : offset]` of a shared field: the general form of [[take]], for a field that sits above
    * another inside one value.
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

  /** One column of a shared field: where it sits, how wide it is, and the two directions of that map — [[lit]] on the
    * host side that composes the value, [[apply]] on the hardware side that reads it back.
    *
    * Fields are low-anchored, so a column's offset is stable however wide the shared field grows, and two columns
    * compose with `|` because they never overlap. A declared field is never a bare column: a [[Union]] derives its
    * columns from its cases and an [[Aggregate]] from its fields. What is left here is the layout that follows data
    * instead of a declaration — the [[PlaPlan]]'s table list — so it stays package-private.
    */
  private[stdlib] case class Column(offset: Int, width: Int):
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

  /** An encoding that unions other encodings: one case per child, each case carrying the child's code.
    *
    * The hardware field is the payload low-anchored with the case's ordinal above it, so reading either column back is
    * a slice (see [[UnionSet]]) — a union names a layout, it costs no logic. All children share one payload column,
    * which is sound because a value belongs to exactly one case and the case says which encoding the payload is in.
    */
  trait Union:
    /** This case's payload: the child encoding whose code the payload column carries. */
    def payload: Encoding

    /** The bits this case needs in the payload column: its child's own field width. */
    def childWidth: Int

  /** The settable side of a [[Union]]: the case list it lays out, and everything derived from it.
    *
    * The cases are the union's declaration — one representative per case, each carrying its child's width — and they
    * decide the layout: the payload column is as wide as the widest child, the tag column as wide as the case count
    * needs, and `lit` puts a case's code where those columns say. Nothing outside this trait knows an offset.
    */
  trait UnionSet[U <: Union & scala.reflect.Enum]:
    /** One case instance per variant, in tag order; the payload is a representative, only the case matters. */
    def cases: Seq[U]

    /** The payload column's width: the widest child's field. */
    final def payloadWidth: Int = cases.map(_.childWidth).max

    /** The tag column's width: what the case count needs. Zero for a single-case union, which carries no tag. */
    final def tagWidth: Int = log2Ceil(cases.size)

    /** The union's field width: payload and tag together. */
    final def width: Int = payloadWidth + tagWidth

    /** A case's code: its payload in the low column, its ordinal above. */
    final def lit(v: U): Int = v.payload.lit | (v.ordinal << payloadWidth)

    /** A case's tag code, for callers that compare tags themselves. */
    final def tagOf(v: U): Int = v.ordinal

    /** The payload column: every case's child code lives here, low-anchored. */
    final def payload[LHS <: Referable[UInt]](u: LHS)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] = Encoding.take(u, payloadWidth)

    /** The tag column: which case a field belongs to. */
    final def tag[LHS <: Referable[UInt]](u: LHS)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] =
      require(tagWidth > 0, "a single-case union carries no tag column")
      Encoding.slice(u, payloadWidth, tagWidth)

    /** Whether a field carries one case: the tag test, so no caller compares a tag by hand. */
    final def isCase[LHS <: Referable[UInt]](v: U, u: LHS)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[Bool] = tag(u) === tagOf(v).U(tagWidth)

    /** Apply one handler per case and let the tag choose between them.
      *
      * `entries` names every case in the order the mux is built — the first is the default and the rest are its tests,
      * which only decides the mux's shape: the tags are mutually exclusive, so the order never changes the function.
      * Each handler takes its case's payload column, which is how a unit reads its own op code out of the shared field.
      */
    final def dispatch[T <: Data](u: Referable[UInt])(entries: (U, Node[UInt] => Referable[T])*)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Referable[T] =
      val cases = entries.map(_._1)
      val values = entries.map((v, entry) => entry(Encoding.take(u, v.childWidth)))
      muxCase(values.head, cases.tail.zip(values.tail).map((v, value) => (isCase(v, u), value)))

  /** A payload of several fields at once: each field owns a column, low-anchored in declaration order.
    *
    * The counterpart of [[Union]]: a union's cases are alternatives that share one payload column, an aggregate's
    * fields are simultaneous values that each get one (a pc and an epoch, an address and a length). Like a union it
    * derives its columns from the declaration, so a caller states neither an offset nor a width, and reading a field is
    * a slice.
    */
  trait Aggregate:
    /** The fields in column order, low to high: the name a reader asks for, and the bits it occupies. */
    def fields: Seq[(String, Int)]

    /** The shared field's width: every field end to end. */
    final def width: Int = fields.map(_._2).sum

    /** Where a field sits in the shared field: the bits below it. */
    final def offset(name: String): Int =
      require(fields.exists(_._1 == name), s"no aggregate field '$name'; the fields are $names")
      fields.takeWhile(_._1 != name).map(_._2).sum

    /** How wide a field is. */
    final def fieldWidth(name: String): Int =
      require(fields.exists(_._1 == name), s"no aggregate field '$name'; the fields are $names")
      fields.find(_._1 == name).get._2

    /** Host side: an encoding's code in one field, for the code that composes a shared value. */
    final def lit(name: String, code: Encoding): Int = code.lit << offset(name)

    /** Hardware side: read a field back out of the shared value. */
    final def read[LHS <: Referable[UInt]](name: String, u: LHS)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] = Encoding.slice(u, offset(name), fieldWidth(name))

    private def names: String = fields.map(_._1).mkString(", ")

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
