package nzea_fpga.boards.tangnano20k

import chisel3._
import nzea_fpga.ResetSync

/** Tang Nano 20K pin wrapper.
  *
  *   - POR: 100-cycle reset pulse at power-up, independent of s1
  *   - Reset: `s1 || por` → Core implicit reset (pulled up; press = low → running)
  *   - LED: Core outputs active-high → invert for board active-low
  */
class TangNano20kTop(clockHz: Int)(implicit config: nzea_config.core.CoreConfig) extends RawModule {
  private val clkFreq = 100_000_000
  private val baudRate = 115200

  val clk = IO(Input(Clock()))
  val s1 = IO(Input(Bool()))
  val s2 = IO(Input(Bool()))
  val uart_rx = IO(Input(Bool()))
  val uart_tx = IO(Output(Bool()))
  val led = IO(Output(UInt(6.W)))

  // POR: 100-cycle reset pulse at power-up
  val porCnt = withClockAndReset(clk, false.B) { RegInit(0.U(7.W)) }
  val por = porCnt < 100.U
  when(por) { porCnt := porCnt + 1.U }

  val s1r = ResetSync(s1, activeHigh = true, clk)

  val core = withClockAndReset(clk, s1r || por) {
    Module(new TangNano20kCore(clkFreq, baudRate))
  }

  core.io.switch := s2
  uart_tx := core.io.uart_tx
  core.io.uart_rx := uart_rx
  led := ~core.io.led
}
