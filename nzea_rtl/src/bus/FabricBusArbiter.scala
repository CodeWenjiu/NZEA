package nzea_rtl

import chisel3._

/** Round-robin multi-master arbiter for FabricBusRW.
  *
  * This is a thin wrapper over [[FabricBusRWCrossbar]] with one downstream target.
  * All addresses are accepted (single full-range slave).
  */
class FabricBusRWArbiter(
  numMasters: Int,
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int,
  idWidth: Int,
  outstanding: Int = 4
) extends Module {
  val io = IO(new Bundle {
    val in = Vec(numMasters, Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)))
    val out = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
    val decodeMiss = Output(Vec(numMasters, Bool()))
  })

  private val fullRange = Seq(FabricAddrRange(base = 0, size = BigInt(1) << addrWidth))
  private val xbar = Module(new FabricBusRWCrossbar(
    numMasters = numMasters,
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    userWidth = userWidth,
    idWidth = idWidth,
    ranges = fullRange,
    perSlaveOutstanding = outstanding
  ))

  xbar.io.in <> io.in
  io.out <> xbar.io.out(0)
  io.decodeMiss := xbar.io.decodeMiss
}
