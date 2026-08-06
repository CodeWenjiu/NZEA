package nzea_core.frontend.bp

import chisel3._

/** Branch prediction update from BRU: pc, taken, target (next_pc), plus a
  * call/ret classification for the RAS (push is driven from the execution
  * side; pop from the commit side).
  */
class BpUpdate extends Bundle {
  val pc      = UInt(32.W)
  val taken   = Bool()
  val target  = UInt(32.W)
  val is_call = Bool() // JAL/JALR linking to x1 (RAS push, execution side)
}
