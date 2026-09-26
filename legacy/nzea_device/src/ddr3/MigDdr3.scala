package nzea_device.ddr3

import chisel3._
import chisel3.experimental.Analog

/** MIG 7-series DDR3 controller — Verilog IP BlackBox.
  *
  * Native UI, 4:1 ratio → 128-bit data at 100 MHz UI. Ports match mig_b.prj configuration.
  */
class MigDdr3 extends ExtModule {
  override def desiredName = "mig_ddr3"

  // ── System ──
  val sys_clk_i = IO(Input(Clock()))
  val sys_rst = IO(Input(Bool()))

  // ── DDR3 physical ──
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

  // ── Native UI ──
  val ui_clk = IO(Output(Clock()))
  val ui_clk_sync_rst = IO(Output(Bool()))
  val init_calib_complete = IO(Output(Bool()))
  // Command
  val app_addr = IO(Input(UInt(29.W)))
  val app_cmd = IO(Input(UInt(3.W)))
  val app_en = IO(Input(Bool()))
  val app_rdy = IO(Output(Bool()))
  // Write data
  val app_wdf_data = IO(Input(UInt(128.W)))
  val app_wdf_wren = IO(Input(Bool()))
  val app_wdf_end = IO(Input(Bool()))
  val app_wdf_rdy = IO(Output(Bool()))
  val app_wdf_mask = IO(Input(UInt(16.W)))
  // Read data
  val app_rd_data = IO(Output(UInt(128.W)))
  val app_rd_data_end = IO(Output(Bool()))
  val app_rd_data_valid = IO(Output(Bool()))
  // Misc
  val app_sr_req = IO(Input(Bool()))
  val app_sr_active = IO(Output(Bool()))
  val app_ref_req = IO(Input(Bool()))
  val app_ref_ack = IO(Output(Bool()))
  val app_zq_req = IO(Input(Bool()))
  val app_zq_ack = IO(Output(Bool()))
}
