package nzea_cache.bank

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.config.Replacement
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The replacement state a set-associative cache keeps: the victim of a set, and the touch that updates the policy.
  *
  * The state is per set because the policy is: tree-PLRU keeps `ways - 1` bits per set (the classic tree, bit `i` of a
  * node saying which of its two subtrees is older), and Random keeps one shared LFSR — advanced on every touch and
  * fixed-seed, so a run is reproducible. The class declares its state in the block that owns the cache, so the two
  * caches share one implementation without an IO boundary between the policy and the hit path that reads it.
  *
  * The touch and the victim are the whole interface: the hit path touches on a hit, the refill touches on install, and
  * a full set asks for the victim instead of falling back to a fixed way.
  */
final class ReplacementState(sets: Int, ways: Int, policy: Replacement)(using
    ClockScope,
    ResetScope,
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
):
    private val setBits = log2Ceil(sets)
    private val wayBits = log2Ceil(ways)
    private val treeBits = ways - 1

    // PLRU: one tree per set, its root at the top bit. The tree is a bit of history, so it resets to zero.
    private val trees = Reg(Vec(sets, UInt(treeBits)))

    when(!summon[ResetScope].reset.asBool) {
        for s <- 0 until sets do trees(s) := 0.U(treeBits)
    }

    // Random: an MCG (`x*5+1` mod 2^16, a full period), advanced once per touch.
    private val lfsr = RegInit(1.U(16))

    /** The way a full set victimizes: the PLRU tree's answer, or the LFSR's low bits. */
    def way(set: Referable[UInt]): Referable[UInt] = policy match
        case Replacement.Plru   => victimBits(Node(trees.ref(set).asBits), ways).asUInt
        case Replacement.Random => Node(lfsr.asBits).bits(wayBits - 1, 0).asUInt

    /** Record a use of `touched` in `set`: the PLRU tree learns the way is the most recent, Random steps its LFSR. */
    def touch(set: Referable[UInt], touched: Referable[UInt]): Unit = policy match
        case Replacement.Plru =>
            val next = nextBits(Node(trees.ref(set).asBits), Node(touched.asBits), ways)
            for s <- 0 until sets do when(set === s.U(setBits)) { trees(s) := next.asUInt }
        case Replacement.Random =>
            lfsr := addWrap(addWrap(lfsr << 2, lfsr, 16), 1.U(16), 16)

    // ── The tree, over `n`-way subtrees ──
    // For `n > 2` the left subtree is the high ways: the root's bit says it is older, and the returned index's top
    // bit is that same bit, so the two always agree. A touch in one subtree updates only that subtree.

    /** The way `state` points at for an `n`-way subtree. */
    private def victimBits(state: Node[Bits], n: Int): Referable[Bits] =
        if n > 2 then
            val right = 1 << (log2Ceil(n) - 1)
            val left = n - right
            val leftOlder = state.bit(n - 2)
            val rightWay = victimBits(state.bits(right - 2, 0), right)
            if left > 1 then
                val leftWay = victimBits(state.bits(n - 3, right - 1), left)
                (leftOlder.asBits ## (leftOlder ? (leftWay, rightWay)))
            else (leftOlder.asBits ## (leftOlder ? (0.B(1), rightWay)))
        else if n == 2 then state.bits(0, 0)
        else 0.B(1)

    /** The next `n`-way subtree state after touching `touch`. */
    private def nextBits(state: Node[Bits], touch: Node[Bits], n: Int): Referable[Bits] =
        if n > 2 then
            val right = 1 << (log2Ceil(n) - 1)
            val left = n - right
            // Touching the high ways (`touch`'s top bit set) makes the low ways older, and vice versa.
            val leftOlder = !touch.bit(log2Ceil(n) - 1)
            val rightNext = nextBits(state.bits(right - 2, 0), touch.bits(log2Ceil(right) - 1, 0), right)
            if left > 1 then
                val leftState = state.bits(n - 3, right - 1)
                val leftNext = nextBits(leftState, touch.bits(log2Ceil(left) - 1, 0), left)
                (leftOlder.asBits ## (leftOlder ? (leftState, leftNext)) ## (leftOlder ? (
                    rightNext,
                    state.bits(right - 2, 0)
                )))
            else (leftOlder.asBits ## (leftOlder ? (rightNext, state.bits(right - 2, 0))))
        else if n == 2 then (!touch.bit(0)).asBits
        else 0.B(1)
