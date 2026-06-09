package nzea_fpga.boards.lxb_artix7

import chisel3._
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig

/** A7-Lite FPGA top-level wrapper (50 MHz, active-low reset).
  *
  * Ports match the A7_lite.xdc constraint file exactly. Instantiates NzeaTile with HelloFPGA platform (sim=false) and
  * adds:
  *   - Reset synchronizer + polarity inversion
  *   - Commit-activity LED with pulse stretching (~0.1s)
  */
class LxbArtix7Top(clockHz: Int)(implicit config: CoreConfig) extends RawModule {
  val CLK_50M = IO(Input(Clock()))
  val RESET = IO(Input(Bool())) // active-low button
  val UART_TX = IO(Output(Bool()))
  val UART_RX = IO(Input(Bool()))
  val LED1 = IO(Output(Bool())) // finish indicator
  val LED2 = IO(Output(Bool())) // commit activity (stretched)

  // Dummy reset for sync chain (never asserted; power-on init only)
  val noReset = Wire(Bool())
  noReset := false.B

  // ── Reset synchronizer (board RESET is active-low) ─────────
  val resetS1 = withClockAndReset(CLK_50M, noReset) { RegInit(true.B) }
  resetS1 := RESET
  val resetS2 = withClockAndReset(CLK_50M, noReset) { RegInit(true.B) }
  resetS2 := resetS1
  val rst = !resetS2 // active-high, synchronized

  // ── NzeaTile ───────────────────────────────────────────────
  val tile: nzea_tile.NzeaTile = withClockAndReset(CLK_50M, rst) {
    Module(new nzea_tile.NzeaTile(sim = false, platform = SynthPlatform.HelloFPGA, clockHz = clockHz))
  }

  val tileIo = tile.io.asInstanceOf[nzea_tile.platform.hellofpga.TileIo]

  // ── UART ───────────────────────────────────────────────────
  UART_TX := tileIo.fpga_uart.txd
  tileIo.fpga_uart.rxd := UART_RX
  tileIo.fpga_uart.ctsn := false.B

  // ── Finish LED ─────────────────────────────────────────────
  LED1 := tileIo.fpga_finish

  // ── Commit activity LED (stretch 1-cycle pulse to ~0.1s) ───
  val stretchTarget = (clockHz / 10).U
  val stretchCnt = withClockAndReset(CLK_50M, rst) { RegInit(0.U(26.W)) }
  val commitLed = withClockAndReset(CLK_50M, rst) { RegInit(false.B) }

  when(rst) {
    stretchCnt := 0.U
    commitLed := false.B
  }.otherwise {
    when(tileIo.commit_msg.valid) {
      stretchCnt := stretchTarget
    }.elsewhen(stretchCnt > 0.U) {
      stretchCnt := stretchCnt - 1.U
    }
    commitLed := stretchCnt > 0.U
  }

  LED2 := commitLed
}
