package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType

/** What an execution path reports about the uop it has just finished: the write-back value and the commit-side effects.
  *
  * A path drives `done` as a level and this payload combinationally (a unit holds its completion for as long as the
  * middle holds the uop), so the completion edge samples the payload — the middle's write-back register is what samples
  * it. `mispredict` is the prediction check already resolved: the int path's branch unit tests its resolved next pc
  * against the prediction the instruction was fetched under, and the one-bit verdict rides this payload — ahead of the
  * dispatch's merge, so the comparator never hangs off the merged `next_pc`, and the retire record carries it as made.
  * Every path but the int one leaves the flag clear.
  */
class PathResult(width: Int) extends Bundle:
    val data = Aligned(UInt(width))
    val next_pc = Aligned(UInt(width))
    val csr_type = Aligned(UInt(CsrType.width))
    val csr_data = Aligned(UInt(width))
    val is_mmio = Aligned(Bool())
    val mispredict = Aligned(Bool())
