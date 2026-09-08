package nzea_tile.platform.fpga

import nzea_rtl.{LiteAddrRange, LiteBusRW}

object AddressMap {
  val ram = LiteAddrRange(base = BigInt("80000000", 16), size = BigInt("00020000", 16))
  val uart = LiteAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  val sifiveTestFinisher = LiteAddrRange(base = BigInt("00100000", 16), size = BigInt("00000004", 16))
  val clint = LiteAddrRange(base = BigInt("02000000", 16), size = BigInt("0000c000", 16))
  /** Board DDR3 (MT41K256M16, 512 MB) — external memory bus 1 on lxb_artix7;
    * the boot SRAM (bus 0) keeps the boot role.
    */
  val ddr = LiteAddrRange(base = BigInt("88000000", 16), size = BigInt("20000000", 16))

  /** External-memory regions in bus-index order; bus 0 is always the boot RAM.
    * A board selects how many it has via FpgaBoard.extMemBuses.
    */
  private val extMemRegistry = Seq(ram, ddr)

  def extMem(numBuses: Int): Seq[LiteAddrRange] = {
    require(
      numBuses >= 1 && numBuses <= extMemRegistry.length,
      s"extMemBuses must be in [1, ${extMemRegistry.length}], got $numBuses"
    )
    extMemRegistry.take(numBuses)
  }

  /** Crossbar decode order: external memory buses first (slot i = bus i), then
    * the on-tile devices. Platform.connectDevices wires the slots in the same
    * order.
    */
  def ranges(numExtMem: Int): Seq[LiteAddrRange] =
    extMem(numExtMem) ++ Seq(uart, sifiveTestFinisher, clint)
}
