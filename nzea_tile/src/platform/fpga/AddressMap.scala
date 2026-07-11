package nzea_tile.platform.fpga

import nzea_rtl.{LiteAddrRange, LiteBusRW}

object AddressMap {
  val ram = LiteAddrRange(base = BigInt("80000000", 16), size = BigInt("00020000", 16))
  val uart = LiteAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  val sifiveTestFinisher = LiteAddrRange(base = BigInt("00100000", 16), size = BigInt("00000004", 16))
  val clint = LiteAddrRange(base = BigInt("02000000", 16), size = BigInt("0000c000", 16))
  val ranges: Seq[LiteAddrRange] = Seq(ram, uart, sifiveTestFinisher, clint)
}
