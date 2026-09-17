package nzea_tile.platform.fpga

import chisel3._
import nzea_config.core.CoreConfig
import nzea_device.uart.FabricBusUart
import nzea_device.clint.Clint
import nzea_device.finisher.SifiveTestFinisher
import nzea_rtl.{LiteBusCrossbar, LiteBusRW}
import nzea_tile.platform.BootFsm
import nzea_tile.platform.yosys.SimDeviceDpiBridge

object Platform {

  /** Directional connection between two same-orientation (master-facing, non-Flipped) LiteBusRW endpoints: the
    * crossbar's out port drives req into the tile boundary bus, and the boundary bus returns ready/flush/resp. `<>`
    * cannot be used — both ends declare the same directions.
    */
  private def connectExtMem(boundary: LiteBusRW, fabricOut: LiteBusRW): Unit = {
    boundary.req.bits := fabricOut.req.bits
    boundary.req.valid := fabricOut.req.valid
    fabricOut.req.ready := boundary.req.ready
    fabricOut.req.flush := boundary.req.flush
    boundary.resp.ready := fabricOut.resp.ready
    boundary.resp.flush := fabricOut.resp.flush
    fabricOut.resp.bits := boundary.resp.bits
    fabricOut.resp.valid := boundary.resp.valid
  }

  def connectDevices(
      fabric: LiteBusCrossbar,
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
    // BootFsm resets when external RAM not calibrated — re-runs boot after calibration
    val bootReset = tileReset || !tileIo.extRamCalibDone
    val ramDepth = (AddressMap.ram.size / 4).toInt
    val hexPath = "nzea_sim/sim/tile/hello.hex"
    val bootFsm = withReset(bootReset) { Module(new BootFsm(ramDepth, hexPath)) }
    require(
      bootFsm.mrom.depth <= ramDepth,
      s"hex has ${bootFsm.mrom.depth} words, RAM holds $ramDepth"
    )
    bootFsm.io.boot_en := true.B
    bootFsm.io.rx_valid := false.B
    bootFsm.io.rx_data := 0.U

    cpuReset := bootReset || bootFsm.io.cpu_reset
    tileIo.debug.cpuReset := cpuReset

    if (sim) {
      for (i <- 0 until fabric.io.out.length) {
        val d = Module(new SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        fabric.io.out(i) <> d.io.bus
      }
    } else {
      // Fabric slots [0, numExtMem) → external memory buses (AddressMap.extMem
      // order; bus 0 = boot RAM, board provides SRAM or DDR3 adapter), then the
      // on-tile devices (uart, finisher, clint) in AddressMap.ranges order.
      val numExtMem = tileIo.extRamBus.length
      for (i <- 0 until numExtMem) {
        connectExtMem(tileIo.extRamBus(i), fabric.io.out(i))
      }
      tileIo.extRamBoot <> bootFsm.io.boot

      // UART must stay alive (bootReset domain) while the BootFsm receives a load:
      // cpuReset is held high during sAddr/sSize/sData, which would reset the RX path
      // and lose the very bytes it must decode. Finisher/CLINT stay in cpuReset so a
      // uart-reset clears their state along with the CPU.
      val uart = withReset(bootReset) {
        Module(new FabricBusUart(AddressMap.uart.base, clockHz, 115200, fabricUserWidth, fabricIdWidth))
      }
      val finisher = withReset(cpuReset) {
        Module(new SifiveTestFinisher(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
      }
      val clint = withReset(cpuReset) {
        Module(new Clint(AddressMap.clint.base, fabricUserWidth, fabricIdWidth))
      }

      fabric.io.out(numExtMem) <> uart.io.bus
      fabric.io.out(numExtMem + 1) <> finisher.io.bus
      fabric.io.out(numExtMem + 2) <> clint.io.bus

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
