package nzea_tile.platform.fpga

import nzea_rtl.{FabricAddrRange, FabricBusRW}

object AddressMap {
  val ram = FabricAddrRange(base = BigInt("80000000", 16), size = BigInt("00020000", 16))
  val uart = FabricAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  val sifiveTestFinisher = FabricAddrRange(base = BigInt("00100000", 16), size = BigInt("00000004", 16))
  val clint = FabricAddrRange(base = BigInt("02000000", 16), size = BigInt("0000c000", 16))
  val ranges: Seq[FabricAddrRange] = Seq(ram, uart, sifiveTestFinisher, clint)
}
