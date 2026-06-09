package nzea_tile.platform.yosys

import chisel3._
import nzea_core.dpi.DbusDpiBridge
import nzea_rtl.{FabricAddrRange, FabricBusRW, FabricRWToLiteRW}

object AddressMap {
  val ram = FabricAddrRange(base = BigInt("80000000", 16), size = BigInt("08000000", 16))
  val uart16550 = FabricAddrRange(base = BigInt("10000000", 16), size = BigInt("00000008", 16))
  val sifiveTestFinisher = FabricAddrRange(base = BigInt("00100000", 16), size = BigInt("00000004", 16))
  val clint = FabricAddrRange(base = BigInt("02000000", 16), size = BigInt("0000c000", 16))
  val ranges: Seq[FabricAddrRange] = Seq(ram, uart16550, sifiveTestFinisher, clint)
}

class SimDeviceDpiBridge(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Module {
  val io = IO(new Bundle { val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)) })

  private val toLite = Module(
    new FabricRWToLiteRW(addrWidth, dataWidth, fabricUserWidth = userWidth, idWidth, liteUserWidth = userWidth)
  )

  private val bridge = Module(new DbusDpiBridge(addrWidth, dataWidth, userWidth))
  toLite.io.in.req.valid := io.bus.req.valid; toLite.io.in.req.bits := io.bus.req.bits
  io.bus.req.ready := toLite.io.in.req.ready; io.bus.req.flush := false.B
  toLite.io.in.resp.flush := io.bus.resp.flush; io.bus.resp.valid := toLite.io.in.resp.valid
  io.bus.resp.bits := toLite.io.in.resp.bits; toLite.io.in.resp.ready := io.bus.resp.ready
  bridge.io.bus <> toLite.io.out
}
