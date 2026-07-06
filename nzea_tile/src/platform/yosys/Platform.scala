package nzea_tile.platform.yosys

import chisel3._
import nzea_core.Core
import nzea_core.config.CoreConfig
import nzea_rtl.FabricBusRWCrossbar

object Platform {

  def connectDevices(
      core: Core,
      fabric: FabricBusRWCrossbar,
      tileIo: TileIo,
      cpuReset: Bool,
      tileReset: Bool,
      sim: Boolean,
      addrWidth: Int,
      dataWidth: Int,
      fabricUserWidth: Int,
      fabricIdWidth: Int,
      clockHz: Int = 100_000_000
  )(implicit config: CoreConfig): Unit = {
    cpuReset := tileReset

    val hwPorts = tileIo.yosys_devices.ports

    for (i <- hwPorts.indices) {
      AccessLatency.connect(
        sim,
        clockHz,
        devHz = tileIo.yosys_devices.devHz(i),
        fabricPort = fabric.io.out(i),
        hwPort = hwPorts(i),
        simPortWidth = addrWidth,
        simPortData = dataWidth,
        simPortUser = fabricUserWidth,
        simPortId = fabricIdWidth
      )
    }
  }

}
