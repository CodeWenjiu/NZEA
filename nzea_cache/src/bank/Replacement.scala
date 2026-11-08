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
  * node saying which of its two subtrees is older), Random keeps one shared, fixed-seed LFSR, so a run is reproducible,
  * and NMRU keeps one MRU way per set beside that LFSR, the set's victim being random among the rest. The state lives
  * in the block that owns the cache: no IO boundary between the policy and the hit path that reads it.
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

    // PLRU: one tree per set, its root at the top bit — a bit of history, so it resets to zero.
    private val trees = Reg(Vec(sets, UInt(treeBits)))

    // NMRU: the way each set touched last, the one way a full set must not victimize.
    private val mruWays = Reg(Vec(sets, UInt(wayBits)))

    when(!summon[ResetScope].reset.asBool) {
        for s <- 0 until sets do
            trees(s) := 0.U(treeBits)
            mruWays(s) := 0.U(wayBits)
    }

    // Random: an MCG (`x*5+1` mod 2^16, a full period), advanced once per touch.
    private val lfsr = RegInit(1.U(16))

    /** The way a full set victimizes: the PLRU tree's answer, the LFSR's low bits, or an LFSR pick that skips the set's
      * MRU way.
      */
    def way(set: Referable[UInt]): Referable[UInt] = policy match
        case Replacement.Plru   => victimBits(Node(trees.ref(set).asBits), ways).asUInt
        case Replacement.Random => Node(lfsr.asBits).bits(wayBits - 1, 0).asUInt
        case Replacement.Nmru =>
            val mru = Node(mruWays.ref(set).asBits).asUInt
            val raw = Node(lfsr.asBits).bits(wayBits - 1, 0).asUInt
            // Draw in `0 .. ways - 2`, then step over the MRU: every other way keeps its one-in-(ways - 1) chance.
            val candidate = raw % (ways - 1).U(wayBits)
            val bumped = addWrap(candidate, 1.U(wayBits), wayBits)
            (candidate >= mru) ? (bumped, candidate)

    def touch(set: Referable[UInt], touched: Referable[UInt]): Unit = policy match
        case Replacement.Plru =>
            val next = nextBits(Node(trees.ref(set).asBits), Node(touched.asBits), ways)
            for s <- 0 until sets do when(set === s.U(setBits)) { trees(s) := next.asUInt }
        case Replacement.Random =>
            lfsr := addWrap(addWrap(lfsr << 2, lfsr, 16), 1.U(16), 16)
        case Replacement.Nmru =>
            for s <- 0 until sets do when(set === s.U(setBits)) { mruWays(s) := touched }
            lfsr := addWrap(addWrap(lfsr << 2, lfsr, 16), 1.U(16), 16)

    // The tree over `n`-way subtrees: for `n > 2` the left subtree is the high ways, and the root's bit says it is
    // older — the returned index's top bit is that same bit, so the two always agree. A touch updates only its subtree.

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
