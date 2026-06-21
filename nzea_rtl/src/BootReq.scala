package nzea_rtl

import chisel3._

/** Boot write request: address + data word. */
class BootReq(addrWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val wdata = UInt(32.W)
}
