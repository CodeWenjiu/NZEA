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
      fabricIdWidth: Int
  )(implicit config: CoreConfig): Unit = {
    cpuReset := tileReset
    if (sim) {
      val ram = Module(new SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
      val uart = Module(new SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
      val finisher = Module(new SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
      val clint = Module(new SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
      fabric.io.out(0) <> ram.io.bus
      fabric.io.out(1) <> uart.io.bus
      fabric.io.out(2) <> finisher.io.bus
      fabric.io.out(3) <> clint.io.bus
    } else {
      fabric.io.out(0) <> tileIo.yosys_devices.ram
      fabric.io.out(1) <> tileIo.yosys_devices.uart16550
      fabric.io.out(2) <> tileIo.yosys_devices.sifive_test_finisher
      fabric.io.out(3) <> tileIo.yosys_devices.clint
    }
  }

}
