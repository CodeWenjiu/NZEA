package nzea_tile.platform.hellofpga

import chisel3._
import nzea_core.Core
import nzea_core.config.CoreConfig
import nzea_device.uart.FabricBusUart
import nzea_device.clint.Clint
import nzea_device.finisher.SifiveTestFinisher
import nzea_device.ram.RamFabricSlave
import nzea_rtl.FabricBusRWCrossbar
import nzea_tile.platform.BootFsm
import nzea_tile.platform.yosys.SimDeviceDpiBridge

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
      clockHz: Int
  )(implicit config: CoreConfig): Unit = {
    val bootFsm = Module(new BootFsm)
    bootFsm.io.boot_en := true.B
    bootFsm.io.rx_valid := false.B
    bootFsm.io.rx_data := 0.U
    cpuReset := tileReset || bootFsm.io.cpu_reset

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
      val ram = Module(new RamFabricSlave(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth, AddressMap.ram.base))
      val uart = Module(new FabricBusUart(AddressMap.uart.base, simClkHz = clockHz, baudRate = 115200))
      val finisher = Module(new SifiveTestFinisher(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
      val clint = Module(new Clint(AddressMap.clint.base))

      fabric.io.out(0) <> ram.io.bus
      fabric.io.out(1) <> uart.io.bus
      fabric.io.out(2) <> finisher.io.bus
      fabric.io.out(3) <> clint.io.bus

      tileIo.fpga_uart.txd := uart.io.txd
      tileIo.fpga_uart.rtsn := uart.io.rtsn
      uart.io.rxd := tileIo.fpga_uart.rxd
      uart.io.ctsn := tileIo.fpga_uart.ctsn
      tileIo.fpga_finish := finisher.io.finished

      bootFsm.io.rx_valid := uart.io.boot_rx_valid
      bootFsm.io.rx_data := uart.io.boot_rx_data
      ram.io.boot_wen := bootFsm.io.ram_wen
      ram.io.boot_addr := bootFsm.io.ram_addr
      ram.io.boot_wdata := bootFsm.io.ram_wdata
    }
  }

}
