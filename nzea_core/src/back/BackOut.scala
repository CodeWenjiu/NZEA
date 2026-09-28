package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType

/** Completion report of the backend — the single reverse channel, back to the middle.
  *
  * Two pulses ride it. `complete` is the cycle the backend finishes the uop and loads its exit register; `done` is the
  * cycle after, when the middle retires what that register holds. `data` is that register's content, so it is
  * meaningful from `done` on — a consumer that needs the result early reads `complete` and knows the register lands
  * next cycle. `flush` is the branch resolution that kills the uop in flight, and it always carries a redirect target:
  * a flush redirects the fetch stream, it never just cancels.
  */
class BackOut(width: Int) extends Bundle:
  val complete = Aligned(Bool())
  val done = Aligned(Bool())
  val data = Aligned(UInt(width))
  val next_pc = Aligned(UInt(width))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_data = Aligned(UInt(width))
  val is_mmio = Aligned(Bool())
  val flush = Aligned(Bool())
  val redirect_target = Aligned(UInt(width))
