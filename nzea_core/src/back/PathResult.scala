package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType

/** What an execution path reports about the uop it has just finished: the write-back value plus the commit-side
  * effects.
  *
  * A path drives `done` as a level and this payload combinationally (a unit holds its completion for as long as the
  * middle holds the uop), so the merging point in [[Back]] samples the payload on the completion edge.
  */
class PathResult(width: Int) extends Bundle:
  val data = Aligned(UInt(width))
  val next_pc = Aligned(UInt(width))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_data = Aligned(UInt(width))
  val is_mmio = Aligned(Bool())
