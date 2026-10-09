package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The arithmetic primitives: the two shapes an addition comes in, and the comparator.
  *
  * [[PrefixAdder]] and [[LessThan]] are the general ones, carried as modules: a generate/propagate scan whose every
  * level is `dontTouch`, because a mapper left to itself reads the scan as one function and folds it back into the
  * ripple chain the shape exists to avoid. [[constAdder]] is the specialization for a constant addend — less logic, and
  * a shape the mapping keeps without being told. The two run the same level-by-level span recurrence — [[prefixScan]]
  * for the pair, a single AND-scan for the constant — and [[shiftWithFill]] is the shift both bring the neighbour's
  * span in with.
  */

/** What a prefix primitive is shaped by: its width. */
case class PrefixArithParameter(width: Int) extends Parameter:
    require(width >= 2, s"a prefix primitive needs width >= 2, got $width")

given upickle.default.ReadWriter[PrefixArithParameter] = upickle.default.macroRW

class PrefixArithLayers(parameter: PrefixArithParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The adder's ports: the addends, the carry in, the sum, and the carry out. */
class PrefixAdderIO(parameter: PrefixArithParameter) extends HWBundle(parameter):
    val a = Flipped(UInt(parameter.width))
    val b = Flipped(UInt(parameter.width))
    val cin = Flipped(Bool())
    val sum = Aligned(UInt(parameter.width))
    val cout = Aligned(Bool())

class PrefixAdderProbe(parameter: PrefixArithParameter)
    extends DVBundle[PrefixArithParameter, PrefixArithLayers](parameter)

/** The compare's ports: the two words, and whether the first is the smaller. */
class LessThanIO(parameter: PrefixArithParameter) extends HWBundle(parameter):
    val a = Flipped(UInt(parameter.width))
    val b = Flipped(UInt(parameter.width))
    val lt = Aligned(Bool())

class LessThanProbe(parameter: PrefixArithParameter)
    extends DVBundle[PrefixArithParameter, PrefixArithLayers](parameter)

/** Kogge-Stone prefix adder: `sum = a + b + cin` with O(log2 n) carry depth instead of a ripple chain.
  *
  * The sum is `(a ^ b) ^ carries`, and the carries come from a prefix network over per-bit generate/propagate. Each
  * level is written in the inverting shape a standard cell actually is — `~(g & (p | g'))` is one OAI21, `~(g | (p &
  * g'))` one AOI21 — so a level costs one gate level, where a plain AND-then-OR pair costs two. The pair therefore
  * alternates polarity: an odd level consumes the complements and re-emits the pair, an even level the reverse, and the
  * propagate rides the same alternation (one NOR2 or NAND2 beside the generate's gate). `cin` is folded into bit 0's
  * generate so it travels the network like any other carry.
  *
  * The body is the module's own, and it is what every flow reads: each level's outputs are marked `dontTouch`, so the
  * mapper maps a level on its own instead of folding the levels back into the NAND chain this shape exists to avoid.
  */
@generator
object PrefixAdder extends Generator[PrefixArithParameter, PrefixArithLayers, PrefixAdderIO, PrefixAdderProbe]:
    override def moduleName(parameter: PrefixArithParameter): String = s"PrefixAdder_width${parameter.width}"

    def architecture(parameter: PrefixArithParameter) =
        val io = summon[Interface[PrefixAdderIO]]
        val width = parameter.width
        val aBits: Node[Bits] = Node(io.a.asBits)
        val bBits: Node[Bits] = Node(io.b.asBits)

        val half: Node[Bits] = Node(aBits ^ bBits)
        // The pair starts complemented — `~(a & b)` and `~(a | b)` are one gate each — with `cin` folded into bit 0's
        // generate, so it travels the network like any other carry.
        val carryIn: Node[Bits] = Node(0.B(width - 1) ## io.cin.asBits)
        val (generate, _, inverted) =
            prefixScan(Node(~((aBits & bBits) | ((aBits | bBits) & carryIn))), Node(~(aBits | bBits)), width)

        // The carry into bit k is the generate for bit k - 1, and bit 0 takes `cin`; a complemented pair is read as its
        // complement, which the XOR beside it turns into an XNOR — one gate either way.
        val carry: Node[Bits] =
            if inverted then Node(~(generate.bits(width - 2, 0)) ## io.cin.asBits)
            else Node(generate.bits(width - 2, 0) ## io.cin.asBits)
        io.sum := Node(half ^ carry).asUInt
        io.cout := (if inverted then Node(!generate.bit(width - 1)) else Node(generate.bit(width - 1)))

/** Unsigned `a < b` as a Kogge-Stone borrow scan, not the `<` primitive.
  *
  * `<` left to synthesis maps to a serial borrow chain — one gate per bit chasing the borrow upward (measured at ~0.9
  * ns across 32 bits inside the execute loop) — because the area-cheapest shape for a comparator is a ripple, the same
  * collapse [[PrefixAdder]]'s levels are held against. The borrow has a prefix recurrence of its own: a bit *generates*
  * a borrow when `!a[i] & b[i]`, *propagates* one when `a[i] == b[i]`, and the answer is the borrow that reaches past
  * the top bit — the same O(log2 n) network [[PrefixAdder]] runs for its carries, in the same inverting shapes, so each
  * level is one gate level, and its levels are kept the same way.
  */
@generator
object LessThan extends Generator[PrefixArithParameter, PrefixArithLayers, LessThanIO, LessThanProbe]:
    override def moduleName(parameter: PrefixArithParameter): String = s"LessThan_width${parameter.width}"

    def architecture(parameter: PrefixArithParameter) =
        val io = summon[Interface[LessThanIO]]
        val width = parameter.width
        val aBits: Node[Bits] = Node(io.a.asBits)
        val bBits: Node[Bits] = Node(io.b.asBits)

        // The borrow's generate and propagate, complemented so the pair starts in the form the scan reads first.
        val (generate, _, inverted) = prefixScan(Node(~(~aBits & bBits)), Node(aBits ^ bBits), width)
        // The borrow past the top bit is the answer; a complemented pair is read as its complement.
        io.lt := (if inverted then Node(!generate.bit(width - 1)) else Node(generate.bit(width - 1)))

/** The prefix scan the general primitives run: the pair's rolling-span recurrence, level by level, in the inverting
  * shapes a standard cell is, so each level is one gate level instead of two.
  *
  * After level i, generate[k]/propagate[k] cover the span [k - 2^i + 1 .. k]: shifting right brings in the neighbour's
  * span, and the fill is the shifting side's identity — a zero for an AND that must vanish below the span, a one for an
  * OR. The pair alternates polarity so every level reads the form the previous one produced: a complemented pair is
  * combined with `~(g & (p | g'))` (one OAI21) and re-emitted as the pair, the pair with `~(g | (p & g'))` (one AOI21)
  * and re-emitted complemented; the propagate is one NOR2 or NAND2 beside it. Returns the pair and whether it came out
  * complemented.
  *
  * Every level's pair is marked `dontTouch`: the levels are the shape's point, and a mapper left to itself reads the
  * whole scan as one function and folds it back into the ripple chain it was built to avoid.
  */
private def prefixScan(generate0: Node[Bits], propagate0: Node[Bits], width: Int)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): (Node[Bits], Node[Bits], Boolean) =
    var generate = generate0
    var propagate = propagate0
    var inverted = true

    var level = 0
    while (1 << level) < width do
        val distance = 1 << level
        def shifted(x: Node[Bits], fill: Boolean): Node[Bits] = shiftWithFill(x, distance, width, fill)

        if inverted then
            val g: Node[Bits] = dontTouch(~(generate & (propagate | shifted(generate, true))))
            val p: Node[Bits] = dontTouch(~(propagate | shifted(propagate, false)))
            generate = g
            propagate = p
            inverted = false
        else
            val g: Node[Bits] = dontTouch(~((propagate & shifted(generate, false)) | generate))
            val p: Node[Bits] = dontTouch(~(propagate & shifted(propagate, true)))
            generate = g
            propagate = p
            inverted = true
        level += 1
    (generate, propagate, inverted)

/** `x` shifted down by `distance` with its low bits filled with `fill`: the span a prefix level brings in from its
  * neighbour, and the one piece of machinery both scans share.
  *
  * Concatenation with zeros, not a dynamic shift: `<<` by a `UInt` widens the result to `width + 2^width`, and the
  * distance is a build-time constant anyway. The fill is built in `BigInt` — `1 << 32` on an `Int` wraps the shift
  * amount and the mask silently goes to zero, which breaks the top level of a 64-bit instantiation.
  */
private def shiftWithFill(x: Node[Bits], distance: Int, width: Int, fill: Boolean)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[Bits] =
    val pad: Referable[Bits] = BigInt(0).B(distance)
    val ones: Referable[Bits] = ((BigInt(1) << distance) - 1).B(width + distance)
    (if fill then (x ## pad) | ones else x ## pad).bits(width - 1, 0)

/** `x + c` for a constant `c = 2^k`, as a Kogge-Stone AND-prefix rather than a general adder.
  *
  * A constant addend needs less than a general one: the sum is `x ^ carry` with `carry[j] = x[k] & ... & x[j - 1]` —
  * one AND-prefix scan and one XOR per bit, where [[PrefixAdder]] runs a generate/propagate pair at two gates a level.
  * It is also the shape the mapping keeps on its own (measured), so its levels carry no `dontTouch`: what holds this
  * one is the formula, not a declared boundary. The addend's bit rides the same XOR vector as the carries, so nothing
  * selects between an addend and a carry.
  *
  * The addition is right for any `x`: the bits below the addend's entry are held at one as the scan's identity, so no
  * alignment is assumed — the fetch's `pc + 4` is this with `c = 4`.
  *
  * Only powers of two are carried, because the direct carry formula above is what the mapping was measured to keep for
  * exactly that class. A constant with runs of ones composes the same formula per run (ORs inside a run, ANDs across
  * the gaps); a caller that needs one writes it then, and measures its shape first the way this one was.
  */
def constAdder(x: Referable[UInt], c: BigInt, width: Int)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[UInt] =
    require(width >= 2, s"constAdder needs width >= 2, got $width")
    val anchor = constAdderAnchor(c)
    require(anchor < width, s"constAdder needs width past the addend's bit; got width $width for c $c")
    val xBits: Node[Bits] = Node(x.asBits)
    val addend: BigInt = BigInt(1) << anchor

    // The inclusive AND-scan `x[k] & ... & x[j]`, with the bits below the addend's entry held at one as the identity.
    var scan: Node[Bits] = Node(xBits | (addend - 1).B(width))
    var level = 0
    while (1 << level) < width do
        val previous: Node[Bits] = shiftWithFill(scan, 1 << level, width, true)
        scan = Node(scan & previous)
        level += 1

    // The carry into bit j is `scan[j - 1]` (the scan drops the bits below its anchor), the addend itself into bit k,
    // and nothing into the bits below.
    val carry: Node[Bits] =
        Node((((scan & ((BigInt(1) << width) - addend).B(width)) ## 0.B(1)).bits(width - 1, 0)) | addend.B(width))
    Node(xBits ^ carry).asUInt

/** A constant's anchor: the one bit position it sets, validated here so [[constAdder]]'s guard is one host-side rule.
  */
def constAdderAnchor(c: BigInt): Int =
    require(c > 0 && (c & (c - 1)) == 0, s"constAdder needs a power-of-two constant, got $c")
    c.bitLength - 1

/** [[PrefixAdder]] as a call: the sum alone. */
def prefixAdder(a: Referable[UInt], b: Referable[UInt], cin: Referable[Bool], width: Int)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[UInt] =
    prefixAdderCarry(a, b, cin, width)._1

/** [[PrefixAdder]] as a call with its carry out exposed: `(a + b + cin, carry past bit width - 1)`. One scan answers
  * both, so a caller that needs the borrow of a subtraction ([[prefixSub]]) does not chain a compare and a subtract.
  */
def prefixAdderCarry(a: Referable[UInt], b: Referable[UInt], cin: Referable[Bool], width: Int)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): (Node[UInt], Node[Bool]) =
    val primitive = PrefixAdder.instantiate(PrefixArithParameter(width))
    primitive.io.a := a
    primitive.io.b := b
    primitive.io.cin := cin
    (Node(primitive.io.sum.asBits).asUInt, Node(primitive.io.cout))

/** `(a - b, a < b)` out of one borrow scan: the difference and the borrow of the same subtraction, so a restoring
  * divider's iteration — shift, compare, conditionally subtract — reads both from one network instead of chaining a
  * comparator and a subtractor. The borrow is the carry out of `a + ~b + 1` inverted, which the shared scan already
  * produces.
  */
def prefixSub(a: Referable[UInt], b: Referable[UInt], width: Int)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): (Node[UInt], Node[Bool]) =
    val bBits: Node[Bits] = Node(b.asBits)
    val (difference, carryOut) = prefixAdderCarry(a, Node(~bBits).asUInt, true.B, width)
    (difference, Node(!carryOut))

/** [[LessThan]] as a call. */
def lessThan(a: Referable[UInt], b: Referable[UInt], width: Int)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[Bool] =
    val primitive = LessThan.instantiate(PrefixArithParameter(width))
    primitive.io.a := a
    primitive.io.b := b
    Node(primitive.io.lt)
