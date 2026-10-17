package nzea_rtl.stdlib

import java.lang.foreign.Arena

import scala.annotation.targetName

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Selection helpers the DSL does not ship (it only has the two-way `cond ? (a, b)`).
  *
  * All of them are combinational folds over `?`: the first matching entry wins, and an all-zero one-hot selector yields
  * zero — Chisel's `Mux1H` ORs independently gated values, so an all-zero select aliases an entry there instead.
  *
  * Bit-vector widths are explicit: the DSL keeps an element's width internal, so callers pass the width they already
  * have in scope.
  */

/** Chisel `MuxCase`: first matching `(cond, value)` wins, `default` otherwise. */
def muxCase[T <: Data](
    default: Referable[T],
    mapping: Seq[(Referable[Bool], Referable[T])]
)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Referable[T] =
    mapping.foldRight(default)((entry, acc) => entry._1 ? (entry._2, acc))

/** Chisel `MuxLookup`: `muxCase` over `key === entry`. */
def muxLookup[K <: Data, V <: Data](
    key: Referable[K],
    default: Referable[V]
)(
    mapping: Seq[(Referable[K], Referable[V])]
)(using
    Eq[K, Bool],
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Referable[V] =
    muxCase(default, mapping.map((k, v) => (key === k, v)))

/** Balanced selection over `values` by `index`, the replacement for a dynamic shift's barrel structure.
  *
  * A barrel shifter fans every shift-amount bit out to a whole 32-bit row of muxes, one row per stage, five stages
  * deep. Selecting a *constant* shift instead makes each stage's data a plain rewiring (a constant shift costs
  * nothing), so only the select network is left: a tree, where each index bit reaches one level and every level halves
  * the width it switches. `values.length` must be a power of two, and `index` is read as that many values wide.
  */
def muxTree[T <: Data](index: Referable[UInt], values: Seq[Referable[T]])(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[T] =
    require(values.nonEmpty, "muxTree needs at least one value")
    require(
        (values.length & (values.length - 1)) == 0,
        s"muxTree needs a power-of-two number of values, got ${values.length}"
    )
    val idxBits: Node[Bits] = Node(index.asBits)
    val idx: Node[UInt] = idxBits.asUInt
    def pick(from: Int, size: Int): Node[T] =
        if size == 1 then Node(values(from))
        else
            val half = size / 2
            idx.asBits.bit(log2Ceil(size) - 1) ? (pick(from + half, half), pick(from, half))
    pick(0, values.length)

/** Pairwise reduce: each level combines neighbours, so the expression a caller builds is a balanced tree, not a serial
  * fold. Serial folds are what leave synthesis a chain to map — the execute loop's src-to-src bypass cone measured two
  * gates per fold step.
  */
private def balancedReduce[T](values: Seq[T])(combine: (T, T) => T): T =
    require(values.nonEmpty, "balancedReduce needs at least one value")
    var level: Seq[T] = values
    while level.size > 1 do
        level = level
            .grouped(2)
            .map {
                case Seq(lhs, rhs) => combine(lhs, rhs)
                case Seq(only)     => only
            }
            .toSeq
    level.head

/** Chisel `Mux1H` over bit vectors of the given `width`: the OR of the gated products is [[balancedReduce]], not a
  * serial fold, so `n` values mean `ceil(log2 n)` gate levels, the shape synthesis can keep, however many there are.
  */
def mux1H(
    sel: Referable[UInt],
    values: Seq[Referable[Bits]],
    width: Int
)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[Bits] =
    require(values.nonEmpty, "mux1H needs at least one value")
    require(width >= 1, s"mux1H expects width >= 1, got $width")
    balancedReduce(values.zipWithIndex.map((value, index) => fill(width, sel.asBits.bit(index)) & Node(value.asBits)))(
        (lhs, rhs) => lhs | rhs
    )

@targetName("mux1HUInt")
def mux1H(
    sel: Referable[UInt],
    values: Seq[Referable[UInt]],
    width: Int
)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[UInt] =
    mux1H(sel, values.map(_.asBits), width).asUInt

def mux1H(
    sel: Referable[UInt],
    values: Seq[Referable[Bool]]
)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[Bool] =
    require(values.nonEmpty, "mux1H needs at least one value")
    balancedReduce(values.zipWithIndex.map((value, index) => value & sel.asBits.bit(index)))((lhs, rhs) => lhs | rhs)
