package nzea_fpga.boards.tangnano20k

import chisel3._

/** Tang Nano 20K pin wrapper.
  *
  *   - POR: 100-cycle reset pulse at power-up, independent of s1
  *   - Reset: `s1 || por` → Core implicit reset (pulled up; press = low → running)
  *   - LED: Core outputs active-high → invert for board active-low
  */
class TangNano20kTop(clockHz: Int)(implicit config: nzea_core.config.CoreConfig) extends RawModule {
  private val clkFreq  = 100_000_000
  private val baudRate = 115200

  val clk      = IO(Input(Clock()))
  val s1       = IO(Input(Bool()))
  val s2       = IO(Input(Bool()))
  val uart_rx  = IO(Input(Bool()))
  val uart_tx  = IO(Output(Bool()))
  val led      = IO(Output(UInt(6.W)))

  // POR: 100-cycle reset, ensures all registers properly initialized
  val noReset = Wire(Bool()); noReset := false.B
  val porCnt  = withClockAndReset(clk, noReset) { RegInit(0.U(7.W)) }
  val por     = porCnt < 100.U
  when(por) { porCnt := porCnt + 1.U }

  val core = withClockAndReset(clk, s1 || por) {
    Module(new TangNano20kCore(clkFreq, baudRate))
  }

  core.io.switch   := s2
  uart_tx          := core.io.uart_tx
  core.io.uart_rx  := uart_rx
  led              := ~core.io.led
}
