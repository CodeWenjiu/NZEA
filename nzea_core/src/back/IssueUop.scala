package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.isa.{Backend, CsrType, Gpr}

/** Ready-operand uop: every operand sits in one of three slots ([[src_a]], [[src_b]], [[src_c]]) that the middle
  * resolved from the register file, the immediate and the pc at the instruction's issue, so the backend neither sees
  * operand indexes nor selects a source in its execute cycle. Which slot a unit reads is that backend's own wiring;
  * `pc` and `imm` stay for the units that use them structurally (the fall-through, the branch offset) — the slot
  * carries the same value, but it has come through the operand-form select, so a unit that can read the offset directly
  * reads it here. Held stable by the middle until done/flush, which is what lets the units take a level instead of a
  * handshake.
  *
  * [[rd_index]] is the destination, and it rides only for the bypass network: the backend itself never reads it.
  *
  * `op` is the decoded instruction whole, in the window its backend owns (`nzea_core.back.int.IntOp`,
  * `nzea_core.back.mem.MemOp`), so this contract does not change when a backend adds a unit or an operation. Its width
  * is the configuration's (`nzea_core.back.OpWindow`) and is derived here like every other field's, so a caller hands
  * over the configuration rather than numbers it worked out.
  *
  * `next_pc` is the pc the fetch stream goes on with after this instruction — the fetcher's prediction for it, carried
  * since the fetch. The baseline predicts the fall-through, so on this configuration the field is the core's one `pc +
  * 4`, made where it already sits on the fetch-rate path; a predictor that lands later changes that value and nothing
  * else about the carrying. The branch unit resolves against it (taken ? target : next_pc is the actual stream), and a
  * consumer that needs the fall-through of an instruction on the predicted path reads it here.
  */
class IssueUop(config: CoreConfig) extends Bundle:
  val pc = Aligned(UInt(config.width))
  val next_pc = Aligned(UInt(config.width))
  val imm = Aligned(UInt(config.width))
  val src_a = Aligned(UInt(config.width))
  val src_b = Aligned(UInt(config.width))
  val src_c = Aligned(UInt(config.width))
  val backend = Aligned(UInt(Backend.width))
  val op = Aligned(UInt(OpWindow.width(config)))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_rdata = Aligned(UInt(config.width))
  val rd_index = Aligned(UInt(Gpr.indexWidth))
