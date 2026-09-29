package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType

/** What an execution path reports about the uop it has just finished: the write-back value and the commit-side effects.
  *
  * A path drives `done` as a level and this payload combinationally (a unit holds its completion for as long as the
  * middle holds the uop), so the completion edge samples the payload — the middle's write-back register is what samples
  * it. `redirect_valid` is the branch's own result: it says the fetch stream was predicted wrongly, and
  * `redirect_target` is where it restarts from. Every path but the int one leaves them clear.
  */
class PathResult(width: Int) extends Bundle:
  val data = Aligned(UInt(width))
  val next_pc = Aligned(UInt(width))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_data = Aligned(UInt(width))
  val is_mmio = Aligned(Bool())
  val redirect_valid = Aligned(Bool())
  val redirect_target = Aligned(UInt(width))
