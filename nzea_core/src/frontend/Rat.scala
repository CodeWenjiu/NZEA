package nzea_core.frontend

import chisel3._

/** RAT entry: rob_id and busy. Used for Reg(Vec(32, RatEntry)) in IDU. */
class RatEntry(idWidth: Int) extends Bundle {
  val rob_id = UInt(idWidth.W)
  val busy   = Bool()
}
