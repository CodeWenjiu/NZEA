package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.experimental.{attach, Analog}
import chisel3.util.Counter
import nzea_config.core.CoreConfig
import nzea_device.ddr3.Ddr3PhysIo
import nzea_fpga.ResetSync

class LxbArtix7Top(clockHz: Int, debugIla: Boolean)(implicit config: CoreConfig) extends RawModule {
  val CLK_50M = IO(Input(Clock()))
  val RESET = IO(Input(Bool()))
  val UART_TX = IO(Output(Bool()))
  val UART_RX = IO(Input(Bool()))
  val LED1 = IO(Output(Bool()))
  val LED2 = IO(Output(Bool()))
  val DDR3 = IO(new Ddr3PhysIo)

  val rst_n = ResetSync(RESET, activeHigh = false, CLK_50M)

  val mmcm = Module(new Mmcm50to200)
  mmcm.clk_in1 := CLK_50M
  mmcm.reset := !rst_n
  val clk_100m = mmcm.clk_out2
  val clk_200m = mmcm.clk_out1 // MIG sys clock

  val coreRst = !rst_n || !mmcm.locked

  val lxbConfig = LxbArtix7Config(debugIla)

  val core = withClockAndReset(clk_100m, coreRst) {
    Module(new LxbArtix7Core(lxbConfig))
  }
  core.io.clk_200m := clk_200m
  // DDR3 control pins: per-field because Ddr3PhysIo carries Analog data pins
  // that Ddr3CtrlIo lacks — Chisel `:=` requires the source record to have
  // every field of the sink, so no bulk connect across the subtype boundary.
  DDR3.addr := core.io.ddr3.addr
  DDR3.ba := core.io.ddr3.ba
  DDR3.ras_n := core.io.ddr3.ras_n
  DDR3.cas_n := core.io.ddr3.cas_n
  DDR3.we_n := core.io.ddr3.we_n
  DDR3.ck_p := core.io.ddr3.ck_p
  DDR3.ck_n := core.io.ddr3.ck_n
  DDR3.cke := core.io.ddr3.cke
  DDR3.odt := core.io.ddr3.odt
  DDR3.reset_n := core.io.ddr3.reset_n
  DDR3.dm := core.io.ddr3.dm
  attach(DDR3.dq, core.dq)
  attach(DDR3.dqs_p, core.dqs_p)
  attach(DDR3.dqs_n, core.dqs_n)
  UART_TX := core.io.uart_tx
  core.io.uart_rx := UART_RX

  val (pwmCnt, _) = withClockAndReset(clk_100m, coreRst) { Counter(true.B, 4) }
  val pwm = pwmCnt =/= 3.U
  val blink = withClockAndReset(clk_100m, coreRst) { val c = RegInit(0.U(26.W)); c := c + 1.U; c(25) }
  LED1 := !(blink & pwm)
  LED2 := !(core.io.led_finish & pwm)
}
