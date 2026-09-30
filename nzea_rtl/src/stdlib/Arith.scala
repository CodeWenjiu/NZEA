package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Balanced selection over `values` by `index`, the replacement for a dynamic shift's barrel structure.
  *
  * A barrel shifter fans every shift-amount bit out to a whole 32-bit row of muxes, one row per stage, five stages
  * deep. Selecting a *constant* shift instead makes each stage's data a plain rewiring (a constant shift costs
  * nothing), so only the select network is left: a tree, where each index bit reaches one level and every level halves
  * the width it switches.
  *
  * `values.length` must be a power of two, and `index` is read as that many values wide.
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

/** Kogge-Stone prefix adder: `s = a + b + cin` with O(log2 n) carry depth instead of a ripple chain.
  *
  * The sum is `(a ^ b) ^ carries`, and the carries come from a prefix network over per-bit generate/propagate. The
  * propagate term is `a | b`, not the textbook `a ^ b`: XOR-based propagate makes abc expand the network into NAND
  * chains (16 logic levels, measured), while AND-OR maps straight through, and the one XOR per bit stays in the half
  * sum. `cin` is folded into bit 0's generate so it travels the network like any other carry.
  */
def prefixAdder(a: Referable[UInt], b: Referable[UInt], cin: Referable[Bool], width: Int)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[UInt] =
  require(width >= 2, s"prefixAdder needs width >= 2, got $width")
  val aBits: Node[Bits] = Node(a.asBits)
  val bBits: Node[Bits] = Node(b.asBits)

  val half: Node[Bits] = Node(aBits ^ bBits)
  var generate: Node[Bits] = Node(aBits & bBits)
  var propagate: Node[Bits] = Node(aBits | bBits)

  val carryIn: Node[Bits] = Node(0.B(width - 1) ## cin.asBits)
  generate = Node(generate | (propagate & carryIn))

  // After level i, generate[k]/propagate[k] cover the span [k - 2^i + 1 .. k]: shifting right brings in the neighbour's
  // span, and propagate's identity (1) below the shift keeps each bit's own span intact.
  var level = 0
  while (1 << level) < width do
    val distance = 1 << level
    // Concatenation with zeros, not a dynamic shift: `<<` by a `UInt` widens the result to `width + 2^width`, and the
    // shift amount is a build-time constant anyway. The mask is built in BigInt — `1 << 32` on an `Int` wraps the shift
    // amount and the mask silently goes to zero, which breaks the top level of a 64-bit instantiation.
    val pad: Referable[Bits] = BigInt(0).B(distance)
    val mask: Referable[Bits] = ((BigInt(1) << distance) - 1).B(width + distance)
    val previousGenerate: Node[Bits] = (generate ## pad).bits(width - 1, 0)
    val previousPropagate: Node[Bits] = ((propagate ## pad) | mask).bits(width - 1, 0)
    generate = Node(generate | (propagate & previousGenerate))
    propagate = Node(propagate & previousPropagate)
    level += 1

  // The carry into bit k is generate[k - 1]; bit 0 takes cin.
  Node(half ^ Node(generate.bits(width - 2, 0) ## cin.asBits)).asUInt

/** A pc's fall-through, `pc + 4`, as a Kogge-Stone AND-prefix rather than a general adder.
  *
  * A general adder with a constant addend (`prefixAdder(x, 4, ...)`) collapses under synthesis into a ripple of complex
  * gates — about a gate per two bits, ~1.2 ns across 32 bits (measured) — because that is the area-cheapest shape, and
  * every stage that needs a pc's next value has a single cycle to spend. Written out, the sum is `x ^ carry` with
  * `carry[k] = x[2] & ... & x[k - 1]`: one AND-prefix scan (the same O(log2 n) network [[prefixAdder]] runs for its
  * carries) and one XOR per bit, which keeps the depth at the tree's instead of the chain's.
  *
  * The addition is right for any `x` — the addend's bits below two are clear — and a pc is word-aligned, so the low
  * bits pass through in practice.
  */
def pcPlus4(pc: Referable[UInt], width: Int)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[UInt] =
  require(width >= 3, s"pcPlus4 needs width >= 3, got $width")
  val x: Node[Bits] = Node(pc.asBits)

  // The inclusive AND-scan `x[2] & ... & x[k]`, with the bits below the addend's entry held at one as the identity.
  var scan: Node[Bits] = Node(x | BigInt(3).B(width))
  var level = 0
  while (1 << level) < width do
    val distance = 1 << level
    val pad: Referable[Bits] = BigInt(0).B(distance)
    val mask: Referable[Bits] = ((BigInt(1) << distance) - 1).B(width + distance)
    val previous: Node[Bits] = ((scan ## pad) | mask).bits(width - 1, 0)
    scan = Node(scan & previous)
    level += 1

  // The carry into bit k is `scan[k - 1]` (the scan drops the two bits below its anchor), the addend itself into
  // bit 2, and nothing into the two bits below.
  val carry: Node[Bits] =
    Node((((scan & ((BigInt(1) << width) - 4).B(width)) ## 0.B(1)).bits(width - 1, 0)) | BigInt(4).B(width))
  Node(x ^ carry).asUInt
