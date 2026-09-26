package nzea_cache

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR

// ── Abstract replacement policy ────────────────────────────────────

abstract class ReplacementPolicy {
  def nBits: Int
  def perSet: Boolean
  def way: UInt
  def miss: Unit
  def access(touch_way: UInt): Unit
  def get_next_state(state: UInt, touch_way: UInt): UInt
  def get_replace_way(state: UInt): UInt
}

object ReplacementPolicy {

  def fromString(s: String, n_ways: Int): ReplacementPolicy = s.toLowerCase match {
    case "random" => new RandomReplacement(n_ways)
    case "plru"   => new PseudoLRU(n_ways)
    case t        => throw new IllegalArgumentException(s"unknown replacement policy: $t")
  }

}

// ── Random ─────────────────────────────────────────────────────────

class RandomReplacement(n_ways: Int) extends ReplacementPolicy {
  private val replace = Wire(Bool())
  replace := false.B
  def nBits = 16
  def perSet = false
  private val lfsr = LFSR(nBits, replace)
  def way = lfsr(log2Ceil(n_ways) - 1, 0) % n_ways.U
  def miss = replace := true.B
  def access(touch_way: UInt) = {}
  def get_next_state(state: UInt, touch_way: UInt) = 0.U
  def get_replace_way(state: UInt) = way
}

// ── Pseudo-LRU (tree-based) ────────────────────────────────────────
//
// Tree-PLRU: https://en.wikipedia.org/wiki/Pseudo-LRU#Tree-PLRU
//
//  - 4-way tree example:
//                  bit[2]: ways 3+2 older than ways 1+0
//                  /                                  \
//     bit[1]: way 3 older than way 2    bit[0]: way 1 older than way 0

class PseudoLRU(n_ways: Int) extends ReplacementPolicy {
  def nBits = n_ways - 1
  def perSet = true
  private val state_reg = if (nBits == 0) Reg(UInt(0.W)) else RegInit(0.U(nBits.W))

  def access(touch_way: UInt): Unit = {
    state_reg := get_next_state(state_reg, touch_way)
  }

  def miss = access(way)

  private def stateSlice(state: UInt, hi: Int, lo: Int): UInt = {
    if (hi < lo) 0.U(0.W) else state(hi, lo)
  }

  def get_next_state(state: UInt, touch_way: UInt, tree_nways: Int): UInt = {
    require(
      state.getWidth == (tree_nways - 1),
      s"wrong state bits width ${state.getWidth} for $tree_nways ways"
    )
    require(
      touch_way.getWidth == (log2Ceil(tree_nways) max 1),
      s"wrong encoded way width ${touch_way.getWidth} for $tree_nways ways"
    )

    if (tree_nways > 2) {
      val right_nways = 1 << (log2Ceil(tree_nways) - 1)
      val left_nways = tree_nways - right_nways
      val set_left_older = !touch_way(log2Ceil(tree_nways) - 1)
      val left_subtree_state = stateSlice(state, tree_nways - 3, right_nways - 1)
      val right_subtree_state = stateSlice(state, right_nways - 2, 0)

      if (left_nways > 1) {
        Cat(
          set_left_older,
          Mux(
            set_left_older,
            left_subtree_state,
            get_next_state(left_subtree_state, touch_way(log2Ceil(left_nways) - 1, 0), left_nways)
          ),
          Mux(
            set_left_older,
            get_next_state(right_subtree_state, touch_way(log2Ceil(right_nways) - 1, 0), right_nways),
            right_subtree_state
          )
        )
      } else {
        Cat(
          set_left_older,
          Mux(
            set_left_older,
            get_next_state(right_subtree_state, touch_way(log2Ceil(right_nways) - 1, 0), right_nways),
            right_subtree_state
          )
        )
      }
    } else if (tree_nways == 2) {
      !touch_way(0)
    } else {
      0.U(1.W)
    }
  }

  def get_next_state(state: UInt, touch_way: UInt): UInt = {
    val tw =
      if (touch_way.getWidth < log2Ceil(n_ways))
        util.OHToUInt(util.UIntToOH(touch_way, 1 << log2Ceil(n_ways)))
      else touch_way(log2Ceil(n_ways) - 1, 0)
    get_next_state(state, tw, n_ways)
  }

  def get_replace_way(state: UInt, tree_nways: Int): UInt = {
    require(state.getWidth == (tree_nways - 1), s"wrong state bits width ${state.getWidth} for $tree_nways ways")

    if (tree_nways > 2) {
      val right_nways = 1 << (log2Ceil(tree_nways) - 1)
      val left_nways = tree_nways - right_nways
      val left_subtree_older = state(tree_nways - 2)
      val left_subtree_state = stateSlice(state, tree_nways - 3, right_nways - 1)
      val right_subtree_state = stateSlice(state, right_nways - 2, 0)

      if (left_nways > 1) {
        Cat(
          left_subtree_older,
          Mux(
            left_subtree_older,
            get_replace_way(left_subtree_state, left_nways),
            get_replace_way(right_subtree_state, right_nways)
          )
        )
      } else {
        Cat(
          left_subtree_older,
          Mux(left_subtree_older, 0.U(1.W), get_replace_way(right_subtree_state, right_nways))
        )
      }
    } else if (tree_nways == 2) {
      state(0)
    } else {
      0.U(1.W)
    }
  }

  def get_replace_way(state: UInt): UInt = get_replace_way(state, n_ways)

  def way = get_replace_way(state_reg)
}

// ── Per-set wrappers ───────────────────────────────────────────────

abstract class SeqReplacementPolicy {
  def access(set: UInt): Unit
  def update(valid: Bool, hit: Bool, set: UInt, way: UInt): Unit
  def way: UInt
}

class SeqRandom(n_ways: Int) extends SeqReplacementPolicy {
  val logic = new RandomReplacement(n_ways)
  def access(set: UInt) = {}

  def update(valid: Bool, hit: Bool, set: UInt, way: UInt) = {
    when(valid && !hit) { logic.miss }
  }

  def way = logic.way
}

class SeqPLRU(n_sets: Int, n_ways: Int) extends SeqReplacementPolicy {
  val logic = new PseudoLRU(n_ways)
  val state = SyncReadMem(n_sets, UInt(logic.nBits.W))
  val current_state = Wire(UInt(logic.nBits.W))
  val next_state = Wire(UInt(logic.nBits.W))
  val plru_way = logic.get_replace_way(current_state)

  def access(set: UInt) = {
    current_state := state.read(set)
  }

  def update(valid: Bool, hit: Bool, set: UInt, way: UInt) = {
    val update_way = Mux(hit, way, plru_way)
    next_state := logic.get_next_state(current_state, update_way)
    when(valid) { state.write(set, next_state) }
  }

  def way = plru_way
}

abstract class SetAssocReplacementPolicy {
  def access(set: UInt, touch_way: UInt): Unit
  def way(set: UInt): UInt
}

class SetAssocPLRU(n_sets: Int, n_ways: Int) extends SetAssocReplacementPolicy {
  val logic = new PseudoLRU(n_ways)

  val state_vec =
    if (logic.nBits == 0) Reg(Vec(n_sets, UInt(logic.nBits.W)))
    else RegInit(VecInit(Seq.fill(n_sets)(0.U(logic.nBits.W))))

  def access(set: UInt, touch_way: UInt) = {
    state_vec(set) := logic.get_next_state(state_vec(set), touch_way)
  }

  def way(set: UInt) = logic.get_replace_way(state_vec(set))
}
