package nzea_tile.platform.yosys

import chisel3._
import nzea_core.dpi.DbusDpiBridge
import nzea_rtl.{LiteAddrRange, LiteBusRW}

object AddressMap {
  val ram = LiteAddrRange(base = BigInt("80000000", 16), size = BigInt("08000000", 16))
  val uart16550 = LiteAddrRange(base = BigInt("10000000", 16), size = BigInt("00000008", 16))
  val sifiveTestFinisher = LiteAddrRange(base = BigInt("00100000", 16), size = BigInt("00000004", 16))
  val clint = LiteAddrRange(base = BigInt("02000000", 16), size = BigInt("0000c000", 16))
  val ranges: Seq[LiteAddrRange] = Seq(ram, uart16550, sifiveTestFinisher, clint)
}

class SimDeviceDpiBridge(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Module {
  val io = IO(new Bundle { val bus = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)) })

  private val bridge = Module(new DbusDpiBridge(addrWidth, dataWidth, userWidth, idWidth))

  // Explicit connection to break combinational flush loop (DbusDpiBridge ties req.flush := resp.flush).
  bridge.io.bus.req.valid := io.bus.req.valid
  bridge.io.bus.req.bits := io.bus.req.bits
  io.bus.req.ready := bridge.io.bus.req.ready
  io.bus.req.flush := false.B
  io.bus.resp.valid := bridge.io.bus.resp.valid
  io.bus.resp.bits := bridge.io.bus.resp.bits
  bridge.io.bus.resp.ready := io.bus.resp.ready
  bridge.io.bus.resp.flush := io.bus.resp.flush
}
