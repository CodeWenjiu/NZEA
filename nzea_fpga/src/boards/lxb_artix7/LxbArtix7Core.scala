package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.util._
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig

/** A7-Lite FPGA core logic — instantiates NzeaTile.
  *
  *   - LED1: ~1.5 Hz blink (alive indicator)
  *   - LED2: finisher triggered
  */
class LxbArtix7Core(clockHz: Int)(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val uart_tx    = Output(Bool())
    val uart_rx    = Input(Bool())
    val led_alive  = Output(Bool())
    val led_finish = Output(Bool())
  })

  // ── NzeaTile ───────────────────────────────────────────────
  val tile = Module(
    new nzea_tile.NzeaTile(sim = false, platform = SynthPlatform.HelloFPGA, clockHz = clockHz)
  )
  val tileIo = tile.io.asInstanceOf[nzea_tile.platform.hellofpga.TileIo]

  io.uart_tx := tileIo.fpga_uart.txd
  tileIo.fpga_uart.rxd := io.uart_rx
  tileIo.fpga_uart.ctsn := false.B

  // ── LEDs ───────────────────────────────────────────────────
  val blinkCnt = RegInit(0.U(26.W))
  blinkCnt := blinkCnt + 1.U
  io.led_alive  := blinkCnt(25) // ~1.5 Hz
  io.led_finish := tileIo.fpga_finish
}
