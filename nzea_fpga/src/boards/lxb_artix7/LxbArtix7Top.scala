package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.experimental.Analog
import chisel3.util.Counter
import nzea_core.config.CoreConfig
import nzea_fpga.boards.lxb_artix7.Mmcm50to200

/** A7-Lite FPGA top-level: board-specific IO + reset synchronizer + MMCM.
  *
  * Thin RawModule wrapper. MMCM lifts tile clock from 50 MHz to 100 MHz. All functional logic lives in
  * [[LxbArtix7Core]].
  */
class LxbArtix7Top(clockHz: Int)(implicit config: CoreConfig) extends RawModule {
  val CLK_50M = IO(Input(Clock()))
  val RESET = IO(Input(Bool())) // active-low button
  val UART_TX = IO(Output(Bool()))
  val UART_RX = IO(Input(Bool()))
  val LED1 = IO(Output(Bool()))
  val LED2 = IO(Output(Bool()))

  // DDR3 physical pins — flat ports (RawModule), bundled internally
  val ddr3_dq = IO(Analog(16.W))
  val ddr3_dqs_p = IO(Analog(2.W))
  val ddr3_dqs_n = IO(Analog(2.W))
  val ddr3_addr = IO(Output(UInt(15.W)))
  val ddr3_ba = IO(Output(UInt(3.W)))
  val ddr3_ras_n = IO(Output(Bool()))
  val ddr3_cas_n = IO(Output(Bool()))
  val ddr3_we_n = IO(Output(Bool()))
  val ddr3_ck_p = IO(Output(UInt(1.W)))
  val ddr3_ck_n = IO(Output(UInt(1.W)))
  val ddr3_cke = IO(Output(UInt(1.W)))
  val ddr3_odt = IO(Output(UInt(1.W)))
  val ddr3_reset_n = IO(Output(Bool()))
  val ddr3_dm = IO(Output(UInt(2.W)))

  // ── Reset synchronizer (active-low button → active-high sync) ──
  val noReset = Wire(Bool())
  noReset := false.B
  val resetS1 = withClockAndReset(CLK_50M, noReset) { RegInit(true.B) }
  resetS1 := RESET
  val resetS2 = withClockAndReset(CLK_50M, noReset) { RegInit(true.B) }
  resetS2 := resetS1
  val rst = !resetS2
  val rst_n = resetS2

  // ── MMCM: 50 MHz → 200 MHz + 100 MHz ──
  val mmcm = Module(new Mmcm50to200)
  mmcm.clk_in1 := CLK_50M
  mmcm.reset := !rst_n
  val clk_200m = mmcm.clk_out1
  val clk_100m = mmcm.clk_out2

  // Hold core in reset until MMCM locked
  val coreRst = rst || !mmcm.locked

  // ── Core (100 MHz) ──────────────────────────────────────────
  val core = withClockAndReset(clk_100m, coreRst) {
    Module(new LxbArtix7Core(clockHz))
  }

  UART_TX := core.io.uart_tx
  core.io.uart_rx := UART_RX

  // ── LED PWM dimming (3/4 duty, active-low LEDs) ─────────────
  val (pwmCnt, _) = withClockAndReset(clk_100m, coreRst) { Counter(true.B, 4) }
  val pwm = pwmCnt =/= 3.U // high for 3 out of 4 cycles
  LED1 := !(core.io.led_alive & pwm)
  LED2 := !(core.io.led_finish & pwm)

  // ── DDR3 ──────────────────────────────────────────────────
  core.io.ddr3.clk_200m := clk_200m
  core.io.ddr3.dq <> ddr3_dq
  core.io.ddr3.dqs_p <> ddr3_dqs_p
  core.io.ddr3.dqs_n <> ddr3_dqs_n
  core.io.ddr3.addr <> ddr3_addr
  core.io.ddr3.ba <> ddr3_ba
  core.io.ddr3.ras_n <> ddr3_ras_n
  core.io.ddr3.cas_n <> ddr3_cas_n
  core.io.ddr3.we_n <> ddr3_we_n
  core.io.ddr3.ck_p <> ddr3_ck_p
  core.io.ddr3.ck_n <> ddr3_ck_n
  core.io.ddr3.cke <> ddr3_cke
  core.io.ddr3.odt <> ddr3_odt
  core.io.ddr3.reset_n <> ddr3_reset_n
  core.io.ddr3.dm <> ddr3_dm
}
