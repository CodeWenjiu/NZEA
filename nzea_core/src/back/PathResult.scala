package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType

/** What an execution path reports about the uop it has just finished: the write-back value and the commit-side effects.
  *
  * A path drives `done` as a level and this payload combinationally (a unit holds its completion for as long as the
  * middle holds the uop), so the completion edge samples the payload — the middle's write-back register is what samples
  * it. `is_branch` marks a uop whose resolution can deviate from the prediction it was fetched under, and
  * `pred_next_pc` carries that prediction beside the resolved `next_pc`; the write-back stage tests the two (`is_branch
  * & next_pc =/= pred_next_pc`), where both are registers and out of the back's combinational path. Every path but the
  * int one leaves the flag clear.
  */
class PathResult(width: Int) extends Bundle:
  val data = Aligned(UInt(width))
  val next_pc = Aligned(UInt(width))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_data = Aligned(UInt(width))
  val is_mmio = Aligned(Bool())
  val is_branch = Aligned(Bool())
  val pred_next_pc = Aligned(UInt(width))
