package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** One instruction's residence on an issue channel: `valid` marks the residence, `bits` is held for its whole length,
  * `first` is high in the residence's first cycle.
  *
  * `first` is what tells a stream of instructions apart. The middle admits a new instruction in the cycle the previous
  * one commits, so `valid` can stay high across that boundary — a consumer that latched a result or started a unit on
  * the level's edge would miss every other instruction. Anything that latches, starts or counts keys on `first`.
  */
class IssueSlot[T <: Data](_bits: T) extends Bundle:
    val valid: BundleField[Bool] = Aligned(Bool())
    val first: BundleField[Bool] = Aligned(Bool())
    val bits: BundleField[T] = Aligned(_bits)
