package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.util.Counter
import nzea_core.config.CoreConfig

/** A7-Lite FPGA top-level: board-specific IO + reset synchronizer.
  *
  * Thin RawModule wrapper. All functional logic lives in [[LxbArtix7Core]].
  * ClockHz is fixed at 50MHz (A7-lite oscillator) to prevent accidental drift.
  */
class LxbArtix7Top(clockHz: Int)(implicit config: CoreConfig) extends RawModule {
  val CLK_50M = IO(Input(Clock()))
  val RESET   = IO(Input(Bool())) // active-low button
  val UART_TX = IO(Output(Bool()))
  val UART_RX = IO(Input(Bool()))
  val LED1    = IO(Output(Bool()))
  val LED2    = IO(Output(Bool()))

  // ── Reset synchronizer (active-low button → active-high sync) ──
  val noReset = Wire(Bool())
  noReset := false.B
  val resetS1 = withClockAndReset(CLK_50M, noReset) { RegInit(true.B) }
  resetS1 := RESET
  val resetS2 = withClockAndReset(CLK_50M, noReset) { RegInit(true.B) }
  resetS2 := resetS1
  val rst = !resetS2

  // ── Core ───────────────────────────────────────────────────
  val core = withClockAndReset(CLK_50M, rst) {
    Module(new LxbArtix7Core(clockHz))
  }

  UART_TX := core.io.uart_tx
  core.io.uart_rx := UART_RX

  // ── LED PWM dimming (3/4 duty, active-low LEDs) ───────────
  val (pwmCnt, _) = withClockAndReset(CLK_50M, rst) { Counter(true.B, 4) }
  val pwm = pwmCnt =/= 3.U  // high for 3 out of 4 cycles
  LED1 := !(core.io.led_alive & pwm)
  LED2 := !(core.io.led_finish & pwm)
}
