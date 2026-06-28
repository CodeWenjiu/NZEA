package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.util._
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig
import nzea_device.ram.RamFabricSlave
import nzea_tile.NzeaTile

/** A7-Lite FPGA core logic — NzeaTile + internal SRAM. */
class LxbArtix7Core(clockHz: Int)(implicit config: CoreConfig) extends Module {
  private val addrW = config.width
  private val dataW = config.width
  private val userW = 64
  private val idW = 8

  val io = IO(new Bundle {
    val uart_tx = Output(Bool())
    val uart_rx = Input(Bool())
    val led_alive = Output(Bool())
    val led_finish = Output(Bool())
  })

  val tile = Module(new NzeaTile(sim = false, platform = SynthPlatform.Fpga, clockHz = clockHz))
  val tileIo = tile.io.asInstanceOf[nzea_tile.platform.fpga.TileIo]

  io.uart_tx := tileIo.fpga_uart.txd
  tileIo.fpga_uart.rxd := io.uart_rx
  tileIo.fpga_uart.ctsn := false.B

  val ram = Module(new RamFabricSlave(addrW, dataW, userW, idW, baseAddr = 0x80000000L))
  ram.io.bus <> tileIo.extRamBus
  ram.io.boot <> tileIo.extRamBoot
  tileIo.extRamCalibDone := true.B // SRAM, always ready

  val blinkCnt = RegInit(0.U(26.W))
  blinkCnt := blinkCnt + 1.U
  io.led_alive := blinkCnt(25)
  io.led_finish := tileIo.fpga_finish
}
