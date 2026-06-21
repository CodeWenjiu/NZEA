package nzea_tile.platform.hellofpga

import chisel3._
import nzea_core.Core
import nzea_core.config.CoreConfig
import nzea_device.uart.FabricBusUart
import nzea_device.clint.Clint
import nzea_device.finisher.SifiveTestFinisher
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
    // external RAM calibration done (driven by board; true when no DDR3)
    val ddr3CalibDone = Wire(Bool())
    ddr3CalibDone := tileIo.extRamCalibDone

    // BootFsm resets when external RAM not calibrated — re-runs boot after calibration
    val bootReset = tileReset || !ddr3CalibDone
    val bootFsm = withReset(bootReset) { Module(new BootFsm) }
    require(
      bootFsm.mrom.depth <= (AddressMap.ram.size / 4).toInt,
      s"hex has ${bootFsm.mrom.depth} words, RAM holds ${AddressMap.ram.size / 4}"
    )
    bootFsm.io.boot_en := true.B
    bootFsm.io.rx_valid := false.B
    bootFsm.io.rx_data := 0.U

    cpuReset := tileReset || bootFsm.io.cpu_reset || !ddr3CalibDone

    if (sim) {
      for (i <- 0 until 4) {
        val d = Module(new SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        fabric.io.out(i) <> d.io.bus
      }
    } else {
      // Fabric slot 0 → external RAM (board provides SRAM or DDR3 adapter)
      tileIo.extRamBus.req.bits := fabric.io.out(0).req.bits
      tileIo.extRamBus.req.valid := fabric.io.out(0).req.valid
      fabric.io.out(0).req.ready := tileIo.extRamBus.req.ready
      fabric.io.out(0).req.flush := tileIo.extRamBus.req.flush
      tileIo.extRamBus.resp.ready := fabric.io.out(0).resp.ready
      tileIo.extRamBus.resp.flush := fabric.io.out(0).resp.flush
      fabric.io.out(0).resp.bits := tileIo.extRamBus.resp.bits
      fabric.io.out(0).resp.valid := tileIo.extRamBus.resp.valid
      tileIo.extRamBoot <> bootFsm.io.boot

      val uart = Module(new FabricBusUart(AddressMap.uart.base, simClkHz = clockHz, baudRate = 115200))
      val finisher = Module(new SifiveTestFinisher(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
      val clint = Module(new Clint(AddressMap.clint.base))

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
    }
  }

}
