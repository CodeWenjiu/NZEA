package nzea_core.frontend.bp

import chisel3._
import nzea_config.core.CoreConfig
import nzea_config.core.PayloadSpec

/** Branch prediction update from BRU: pc, taken, target (next_pc), plus a
  * call/ret classification for the RAS (push is driven from the execution
  * side; pop from the commit side). `is_call` exists iff RAS is enabled.
  */
class BpUpdate(implicit config: CoreConfig) extends Bundle {
  val pc      = UInt(32.W)
  val taken   = Bool()
  val target  = UInt(32.W)
  val is_call = if (PayloadSpec.enabled(PayloadSpec.CallExec)) Some(Bool()) else None
}
