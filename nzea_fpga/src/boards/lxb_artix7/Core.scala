package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.util._
import nzea_core.config.CoreConfig
import nzea_device.ram.RamFabricSlave
import nzea_tile.NzeaTile

/** A7-Lite FPGA core logic — NzeaTile + internal SRAM. */
class LxbArtix7Core(cfg: LxbArtix7Config)(implicit config: CoreConfig) extends Module {
  private val addrW = config.width
  private val dataW = config.width

  val io = IO(new Bundle {
    val uart_tx = Output(Bool())
    val uart_rx = Input(Bool())
    val led_alive = Output(Bool())
    val led_finish = Output(Bool())
  })

  val tile = Module(new NzeaTile(cfg.tile))

  val tileIo = tile.io.asInstanceOf[nzea_tile.platform.fpga.TileIo]

  // Derive bus widths from the tile IO type, not hardcoded constants.
  private val userW = tileIo.extRamBus.userWidth
  private val idW = tileIo.extRamBus.idWidth

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
