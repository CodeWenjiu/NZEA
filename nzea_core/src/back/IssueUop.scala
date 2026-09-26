package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.{Backend, CsrType}

/** Ready-operand uop: every operand sits in one of three slots ([[src_a]], [[src_b]], [[src_c]]) that decode already
  * resolved from the register file, the immediate and the pc, so the backend neither sees register indexes nor selects
  * a source in its execute cycle. Which slot a unit reads is that backend's own wiring; `pc` and `imm` stay for the
  * units that use them structurally (the fall-through, the branch offset). Held stable by the middle until done/flush,
  * which is what lets the units take a level instead of a handshake.
  *
  * `op` is the decoded instruction whole, in the window its backend owns (`nzea_core.back.int.IntOp`,
  * `nzea_core.back.mem.MemOp`), so this contract does not change when a backend adds a unit or an operation.
  */
class IssueUop(width: Int) extends Bundle:
  val pc = Aligned(UInt(width))
  val imm = Aligned(UInt(width))
  val src_a = Aligned(UInt(width))
  val src_b = Aligned(UInt(width))
  val src_c = Aligned(UInt(width))
  val backend = Aligned(UInt(Backend.width))
  val op = Aligned(UInt(OpWindow.Width))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_rdata = Aligned(UInt(width))
