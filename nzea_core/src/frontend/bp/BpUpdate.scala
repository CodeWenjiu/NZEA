package nzea_core.frontend.bp

import chisel3._

/** Branch prediction update from BRU: pc, taken, target (next_pc). */
class BpUpdate extends Bundle {
  val pc     = UInt(32.W)
  val taken  = Bool()
  val target = UInt(32.W)
}
