package nzea_tile

import chisel3._
import nzea_rtl.{
  FabricBusRW,
  FabricBusRWRegisterSlice,
  LiteBusRO,
  LiteBusROReqRegisterSlice,
  LiteBusROToFabricRW,
  LiteBusRW,
  LiteBusRWToFabricRW
}

/** IFU → Fabric adapter: register slice + protocol conversion + output slice. */
class IbusAdapter(addrWidth: Int, dataWidth: Int, liteUserWidth: Int, fabricUserWidth: Int, fabricIdWidth: Int)
    extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRO(addrWidth, dataWidth, liteUserWidth))
    val out = new FabricBusRW(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth)
  })

  private val reqSlice = Module(new LiteBusROReqRegisterSlice(addrWidth, dataWidth, liteUserWidth))

  private val toFabric = Module(
    new LiteBusROToFabricRW(addrWidth, dataWidth, liteUserWidth, fabricUserWidth, fabricIdWidth)
  )

  private val outSlice = Module(new FabricBusRWRegisterSlice(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))

  reqSlice.io.in <> io.in
  toFabric.io.in <> reqSlice.io.out
  outSlice.io.in <> toFabric.io.out
  io.out <> outSlice.io.out
}

/** Dbus → Fabric adapter: protocol conversion + output slice. */
class DbusAdapter(addrWidth: Int, dataWidth: Int, liteUserWidth: Int, fabricUserWidth: Int, fabricIdWidth: Int)
    extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRW(addrWidth, dataWidth, liteUserWidth))
    val out = new FabricBusRW(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth)
  })

  private val toFabric = Module(
    new LiteBusRWToFabricRW(addrWidth, dataWidth, liteUserWidth, fabricUserWidth, fabricIdWidth)
  )

  private val outSlice = Module(new FabricBusRWRegisterSlice(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))

  toFabric.io.in <> io.in
  outSlice.io.in <> toFabric.io.out
  io.out <> outSlice.io.out
}
