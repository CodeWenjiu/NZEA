package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType

/** Completion report of the backend — the single reverse channel, back to the middle.
  *
  * `done` is a one-cycle pulse: the backend latches one completion in its exit register and reports it the cycle after.
  * The middle commits that pulse directly (register write-back, CSR write, commit trace), so the reported fields are
  * held stable while it arrives. `flush` is the branch resolution that kills the uop in flight, and it always carries a
  * redirect target: a flush redirects the fetch stream, it never just cancels.
  */
class BackOut(width: Int) extends Bundle:
  val done = Aligned(Bool())
  val data = Aligned(UInt(width))
  val next_pc = Aligned(UInt(width))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_data = Aligned(UInt(width))
  val is_mmio = Aligned(Bool())
  val flush = Aligned(Bool())
  val redirect_target = Aligned(UInt(width))
