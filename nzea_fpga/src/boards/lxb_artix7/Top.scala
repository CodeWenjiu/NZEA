package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.util.Counter
import nzea_core.config.CoreConfig
import nzea_fpga.ResetSync

class LxbArtix7Top(clockHz: Int)(implicit config: CoreConfig) extends RawModule {
  val CLK_50M = IO(Input(Clock()))
  val RESET = IO(Input(Bool()))
  val UART_TX = IO(Output(Bool()))
  val UART_RX = IO(Input(Bool()))
  val LED1 = IO(Output(Bool()))
  val LED2 = IO(Output(Bool()))

  val rst_n = ResetSync(RESET, activeHigh = false, CLK_50M)

  val mmcm = Module(new Mmcm50to200)
  mmcm.clk_in1 := CLK_50M
  mmcm.reset := !rst_n
  val clk_100m = mmcm.clk_out2

  val coreRst = !rst_n || !mmcm.locked

  val core = withClockAndReset(clk_100m, coreRst) {
    Module(new LxbArtix7Core(clockHz))
  }

  UART_TX := core.io.uart_tx
  core.io.uart_rx := UART_RX

  val (pwmCnt, _) = withClockAndReset(clk_100m, coreRst) { Counter(true.B, 4) }
  val pwm = pwmCnt =/= 3.U
  val blink = withClockAndReset(clk_100m, coreRst) { val c = RegInit(0.U(26.W)); c := c + 1.U; c(25) }
  LED1 := !(blink & pwm)
  LED2 := !(core.io.led_finish & pwm)
}
