package nzea_tile

import chisel3._
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig
import nzea_core.dpi.CommitDpiBridge
import nzea_rtl.{
  FabricAddrRange,
  FabricBusRW,
  FabricBusRWCrossbar,
  FabricBusRWRegisterSlice,
  LiteBusROReqRegisterSlice,
  LiteBusROToFabricRW,
  LiteBusRWToFabricRW
}
import nzea_tile.platform.fpga
import nzea_tile.platform.yosys
import nzea_tile.platform.HasCommitMsg
import nzea_cache.SetAssoc

/** Tile address map dispatch. Platform-specific ranges defined in their respective packages. */
object TileAddressMap {

  def forPlatform(platform: SynthPlatform): Seq[FabricAddrRange] = platform match {
    case SynthPlatform.Yosys => yosys.AddressMap.ranges
    case SynthPlatform.Fpga  => fpga.AddressMap.ranges
  }

}

/** CPU tile: [[nzea_core.Core]] plus FabricBus interconnect and per-device external interfaces. Bus fabric: 2 masters
  * (core ibus+dbus) and platform-selected slaves. `sim=true`: slaves are connected to DPI bridges (bus_read/bus_write).
  * `sim=false`: exposes platform-specific HW IO as top-level ports.
  */
class NzeaTile(sim: Boolean, platform: SynthPlatform, clockHz: Int = 100_000_000)(implicit
    config: CoreConfig
) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val ranges = TileAddressMap.forPlatform(platform)
  private val fabricUserWidth = 64
  private val fabricIdWidth = 8

  val io = IO(platform match {
    case SynthPlatform.Yosys => new yosys.TileIo(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth)
    case SynthPlatform.Fpga  => new fpga.TileIo(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth)
  })

  io := DontCare

  val cpuReset = Wire(Bool())
  val core = withReset(cpuReset) { Module(new nzea_core.Core) }

  val ibusReqSlice = Module(
    new LiteBusROReqRegisterSlice(
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      userWidth = core.io.ibus.userWidth
    )
  )

  val icache = Module(
    new SetAssoc(
      nSets = 16,
      nWays = 4,
      lineBits = 32,
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      userWidth = core.io.ibus.userWidth
    )
  )

  icache.io.top <> core.io.ibus

  ibusReqSlice.io.in <> icache.io.bottom

  val ibusToFabric = Module(
    new LiteBusROToFabricRW(
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      liteUserWidth = core.io.ibus.userWidth,
      fabricUserWidth = fabricUserWidth,
      idWidth = fabricIdWidth
    )
  )

  ibusToFabric.io.in <> ibusReqSlice.io.out

  val dbusToFabric = Module(
    new LiteBusRWToFabricRW(
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      liteUserWidth = core.io.dbus.userWidth,
      fabricUserWidth = fabricUserWidth,
      idWidth = fabricIdWidth
    )
  )

  dbusToFabric.io.in <> core.io.dbus

  val fabric = Module(
    new FabricBusRWCrossbar(
      numMasters = 2,
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      userWidth = fabricUserWidth,
      idWidth = fabricIdWidth,
      ranges = ranges,
      perSlaveOutstanding = 8
    )
  )

  val ibusSlice = Module(new FabricBusRWRegisterSlice(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
  val dbusSlice = Module(new FabricBusRWRegisterSlice(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
  ibusSlice.io.in <> ibusToFabric.io.out
  dbusSlice.io.in <> dbusToFabric.io.out
  fabric.io.in(0) <> ibusSlice.io.out
  fabric.io.in(1) <> dbusSlice.io.out

  if (sim) {
    val cb = Module(new CommitDpiBridge)
    cb.io.commit_msg := core.io.commit_msg
  } else {
    io.asInstanceOf[HasCommitMsg].commit_msg := core.io.commit_msg
  }

  platform match {
    case SynthPlatform.Yosys =>
      yosys.Platform.connectDevices(
        core,
        fabric,
        io.asInstanceOf[yosys.TileIo],
        cpuReset,
        reset.asBool,
        sim,
        addrWidth,
        dataWidth,
        fabricUserWidth,
        fabricIdWidth
      )
    case SynthPlatform.Fpga =>
      fpga.Platform.connectDevices(
        core,
        fabric,
        io.asInstanceOf[fpga.TileIo],
        cpuReset,
        reset.asBool,
        sim,
        addrWidth,
        dataWidth,
        fabricUserWidth,
        fabricIdWidth,
        clockHz
      )
  }

}
