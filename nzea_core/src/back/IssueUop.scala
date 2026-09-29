package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.isa.{Backend, CsrType}

/** Ready-operand uop: every operand sits in one of three slots ([[src_a]], [[src_b]], [[src_c]]) that the middle
  * resolved from the register file, the immediate and the pc at the instruction's issue, so the backend neither sees
  * register indexes nor selects a source in its execute cycle. Which slot a unit reads is that backend's own wiring;
  * `pc` and `imm` stay for the units that use them structurally (the fall-through, the branch offset) — the slot
  * carries the same value, but it has come through the operand-form select, so a unit that can read the offset directly
  * reads it here. Held stable by the middle until done/flush, which is what lets the units take a level instead of a
  * handshake.
  *
  * `op` is the decoded instruction whole, in the window its backend owns (`nzea_core.back.int.IntOp`,
  * `nzea_core.back.mem.MemOp`), so this contract does not change when a backend adds a unit or an operation. Its width
  * is the configuration's (`nzea_core.back.OpWindow`) and is derived here like every other field's, so a caller hands
  * over the configuration rather than numbers it worked out.
  *
  * `pc_plus4` is the fall-through, resolved by the middle where the pc is already a held value. The execute cycle is
  * the busiest one in the core, and `pc + 4` does not depend on anything computed there: carrying the value costs a
  * field and takes one adder and its select out of the branch-resolution cone.
  */
class IssueUop(config: CoreConfig) extends Bundle:
  val pc = Aligned(UInt(config.width))
  val pc_plus4 = Aligned(UInt(config.width))
  val imm = Aligned(UInt(config.width))
  val src_a = Aligned(UInt(config.width))
  val src_b = Aligned(UInt(config.width))
  val src_c = Aligned(UInt(config.width))
  val backend = Aligned(UInt(Backend.width))
  val op = Aligned(UInt(OpWindow.width(config)))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_rdata = Aligned(UInt(config.width))
