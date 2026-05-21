package nzea_tile

import chisel3._
import chisel3.util.Valid
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig
import nzea_core.dpi.CommitDpiBridge
import nzea_core.retire.CommitMsg
import nzea_rtl.{
  FabricAddrRange,
  FabricBusRW,
  FabricBusRWCrossbar,
  FabricBusRWRegisterSlice,
  LiteBusROReqRegisterSlice,
  LiteBusROToFabricRW,
  LiteBusRWToFabricRW
}
import nzea_tile.platform.hellofpga
import nzea_tile.platform.yosys

/** Tile address map dispatch. Platform-specific ranges defined in their respective packages. */
object TileAddressMap {
  def forPlatform(platform: SynthPlatform): Seq[FabricAddrRange] = platform match {
    case SynthPlatform.Yosys     => yosys.AddressMap.ranges
    case SynthPlatform.HelloFPGA => hellofpga.AddressMap.ranges
  }
}

/** CPU tile: [[nzea_core.Core]] plus FabricBus interconnect and per-device external interfaces.
  * Bus fabric: 2 masters (core ibus+dbus) and platform-selected slaves.
  * `sim=true`: slaves are connected to DPI bridges (bus_read/bus_write).
  * `sim=false`: exposes platform-specific HW IO as top-level ports.
  */
class NzeaTile(sim: Boolean, platform: SynthPlatform, clockHz: Int = 100_000_000)(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val ranges = TileAddressMap.forPlatform(platform)
  private val fabricUserWidth = 64
  private val fabricIdWidth   = 8

  val io = IO(new Bundle {
    val commit_msg = Output(Valid(new CommitMsg))
    val yosys_devices = new yosys.DeviceBusBundle(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth)
    val fpga_uart     = new hellofpga.UartIo
    val fpga_finish   = Output(Bool())
  })
  io := DontCare

  // Boot FSM: holds core in reset during UART program load
  val bootFsm = Module(new hellofpga.BootFsm)
  bootFsm.io.boot_en := true.B
  bootFsm.io.rx_valid := false.B  // default; overridden in HelloFPGA hw
  bootFsm.io.rx_data  := 0.U
  val cpuReset = reset.asBool || bootFsm.io.cpu_reset

  val core = withReset(cpuReset) { Module(new nzea_core.Core) }

  val ibusReqSlice = Module(new LiteBusROReqRegisterSlice(
    addrWidth = addrWidth, dataWidth = dataWidth, userWidth = core.io.ibus.userWidth
  ))
  ibusReqSlice.io.in <> core.io.ibus

  val ibusToFabric = Module(new LiteBusROToFabricRW(
    addrWidth = addrWidth, dataWidth = dataWidth,
    liteUserWidth = core.io.ibus.userWidth, fabricUserWidth = fabricUserWidth, idWidth = fabricIdWidth
  ))
  ibusToFabric.io.in <> ibusReqSlice.io.out

  val dbusToFabric = Module(new LiteBusRWToFabricRW(
    addrWidth = addrWidth, dataWidth = dataWidth,
    liteUserWidth = core.io.dbus.userWidth, fabricUserWidth = fabricUserWidth, idWidth = fabricIdWidth
  ))
  dbusToFabric.io.in <> core.io.dbus

  val fabric = Module(new FabricBusRWCrossbar(
    numMasters = 2, addrWidth = addrWidth, dataWidth = dataWidth,
    userWidth = fabricUserWidth, idWidth = fabricIdWidth,
    ranges = ranges, perSlaveOutstanding = 8
  ))

  val ibusSlice = Module(new FabricBusRWRegisterSlice(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
  val dbusSlice = Module(new FabricBusRWRegisterSlice(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
  ibusSlice.io.in <> ibusToFabric.io.out
  dbusSlice.io.in <> dbusToFabric.io.out
  fabric.io.in(0) <> ibusSlice.io.out
  fabric.io.in(1) <> dbusSlice.io.out

  if (sim) {
    platform match {
      case SynthPlatform.Yosys =>
        val ram      = Module(new yosys.SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val uart     = Module(new yosys.SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val finisher = Module(new yosys.SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val clint    = Module(new yosys.SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        fabric.io.out(0) <> ram.io.bus
        fabric.io.out(1) <> uart.io.bus
        fabric.io.out(2) <> finisher.io.bus
        fabric.io.out(3) <> clint.io.bus

      case SynthPlatform.HelloFPGA =>
        val ram      = Module(new yosys.SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val uart     = Module(new yosys.SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val finisher = Module(new yosys.SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val clint    = Module(new yosys.SimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        fabric.io.out(0) <> ram.io.bus
        fabric.io.out(1) <> uart.io.bus
        fabric.io.out(2) <> finisher.io.bus
        fabric.io.out(3) <> clint.io.bus
    }

    val cb = Module(new CommitDpiBridge)
    cb.io.commit_msg := core.io.commit_msg
  } else {
    io.commit_msg := core.io.commit_msg

    platform match {
      case SynthPlatform.Yosys =>
        fabric.io.out(0) <> io.yosys_devices.ram
        fabric.io.out(1) <> io.yosys_devices.uart16550
        fabric.io.out(2) <> io.yosys_devices.sifive_test_finisher
        fabric.io.out(3) <> io.yosys_devices.clint

      case SynthPlatform.HelloFPGA =>
        val ram = Module(new hellofpga.RamFabricSlave(
          addrWidth, dataWidth, fabricUserWidth, fabricIdWidth, hellofpga.AddressMap.ram.base
        ))
        val uart     = Module(new hellofpga.FabricBusUart(simClkHz = clockHz, baudRate = 100000))
        val finisher = Module(new hellofpga.SifiveTestFinisher(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val clint    = Module(new hellofpga.Clint(hellofpga.AddressMap.clint.base))

        fabric.io.out(0) <> ram.io.bus
        fabric.io.out(1) <> uart.io.bus
        fabric.io.out(2) <> finisher.io.bus
        fabric.io.out(3) <> clint.io.bus
        io.fpga_uart.txd       := uart.io.txd
        io.fpga_uart.rtsn      := uart.io.rtsn

        uart.io.rxd  := io.fpga_uart.rxd
        uart.io.ctsn := io.fpga_uart.ctsn
        io.fpga_finish := finisher.io.finished

        // Boot FSM: UART RX → RAM write
        bootFsm.io.rx_valid := uart.io.boot_rx_valid
        bootFsm.io.rx_data  := uart.io.boot_rx_data
        ram.io.boot_wen   := bootFsm.io.ram_wen
        ram.io.boot_addr  := bootFsm.io.ram_addr
        ram.io.boot_wdata := bootFsm.io.ram_wdata
    }
  }
}
