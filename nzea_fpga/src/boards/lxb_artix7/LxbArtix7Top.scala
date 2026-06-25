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

  val coreRst = rst || !mmcm.locked

  // ── Ddr3TestCore (100 MHz) — minimal DDR3 validation ─────────
  val core = withClockAndReset(clk_100m, coreRst) {
    Module(new Ddr3TestCore(addrW = 32, dataW = 32, userW = 64, idW = 8))
  }

  core.io.rst_n := rst_n

  // ── LEDs ───────────────────────────────────────────────────
  val (pwmCnt, _) = withClockAndReset(clk_100m, coreRst) { Counter(true.B, 4) }
  val pwm = pwmCnt =/= 3.U

  val blink = withClockAndReset(clk_100m, coreRst) {
    val cnt = RegInit(0.U(26.W)); cnt := cnt + 1.U; cnt(25)
  }

  LED1 := !(blink & pwm)
  LED2 := !(core.io.pass & pwm)

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

  // ── ILA (post-synthesis Vivado IP matches module name "u_ila_0") ──
  val ila = Module(new IlaProbes)
  ila.clk := clk_100m
  ila.probe0 := core.io.dbg_calib_done
  ila.probe1 := core.io.dbg_app_rdy
  ila.probe2 := core.io.dbg_app_rd_data_valid
  ila.probe3 := core.io.dbg_app_wdf_rdy
  ila.probe4 := core.io.dbg_app_en
  ila.probe5 := core.io.dbg_app_cmd
  ila.probe6 := core.io.dbg_app_addr
  ila.probe7 := core.io.dbg_rd_data
  ila.probe8 := core.io.dbg_fsm
  ila.probe9 := core.io.pass
  ila.probe10 := core.io.testBits
  dontTouch(ila.clk)
}
