package nzea_device.ddr3

import chisel3._

/** MIG native UI interface bundle — from the MIG controller's perspective.
  *
  * MIG receives: addr, cmd, en, wdf_data, wdf_wren, wdf_end, wdf_mask MIG produces: rdy, wdf_rdy, rd_data,
  * rd_data_valid, calib_done
  *
  * Use directly for the subsystem (wrapping MIG). Use [[Flipped]] for the adapter (driving MIG).
  */
class MigUiIo extends Bundle {
  // Command
  val app_addr = Input(UInt(29.W))
  val app_cmd = Input(UInt(3.W))
  val app_en = Input(Bool())
  val app_rdy = Output(Bool())
  // Write data
  val app_wdf_data = Input(UInt(128.W))
  val app_wdf_wren = Input(Bool())
  val app_wdf_end = Input(Bool())
  val app_wdf_rdy = Output(Bool())
  val app_wdf_mask = Input(UInt(16.W))
  // Read data
  val app_rd_data = Output(UInt(128.W))
  // Marks the LAST beat of a read-data burst. For 4:1 PHY the 128-bit data
  // word is delivered over two UI beats (app_rd_data_valid stays high for both,
  // app_rd_data_end on the second); sampling on valid alone can capture the
  // first (partial) beat. Consumers must sample on valid && end.
  val app_rd_data_end = Output(Bool())
  val app_rd_data_valid = Output(Bool())
  // Status
  val calib_done = Output(Bool())
  // User-interface clock/reset (from MIG; the adapter's ui-domain driver)
  val ui_clk = Output(Clock())
  val ui_rst = Output(Bool())
}
