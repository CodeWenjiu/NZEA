package nzea_device.ddr3

import chisel3._
import chisel3.experimental.Analog

/** DDR3 subsystem: wraps MigDdr3, exposes MIG native UI + physical pins.
  *
  * MMCM is external (in LxbArtix7Top); this module receives clk_200m directly.
  */
class Ddr3Subsystem(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int
) extends Module {
  require(dataWidth == 32)

  val io = IO(new Bundle {
    val rst_n = Input(Bool())

    // ── MIG inputs (from Ddr3Adapter) ──
    val app_addr = Input(UInt(29.W))
    val app_cmd = Input(UInt(3.W))
    val app_en = Input(Bool())
    val app_wdf_data = Input(UInt(128.W))
    val app_wdf_wren = Input(Bool())
    val app_wdf_end = Input(Bool())
    val app_wdf_mask = Input(UInt(16.W))
    // ── MIG outputs (to Ddr3Adapter) ──
    val app_rdy = Output(Bool())
    val app_wdf_rdy = Output(Bool())
    val app_rd_data = Output(UInt(128.W))
    val app_rd_data_valid = Output(Bool())
    val calib_done = Output(Bool())

    // ── DDR3 physical ──
    val ddr3 = new Ddr3PhysIo
  })

  val mig = Module(new MigDdr3)
  mig.sys_clk_i := io.ddr3.clk_200m
  mig.sys_rst := !io.rst_n

  // Connect Ddr3PhysIo ↔ MigDdr3 flat ports
  io.ddr3.dq <> mig.ddr3_dq
  io.ddr3.dqs_p <> mig.ddr3_dqs_p
  io.ddr3.dqs_n <> mig.ddr3_dqs_n
  io.ddr3.addr := mig.ddr3_addr
  io.ddr3.ba := mig.ddr3_ba
  io.ddr3.ras_n := mig.ddr3_ras_n
  io.ddr3.cas_n := mig.ddr3_cas_n
  io.ddr3.we_n := mig.ddr3_we_n
  io.ddr3.ck_p := mig.ddr3_ck_p
  io.ddr3.ck_n := mig.ddr3_ck_n
  io.ddr3.cke := mig.ddr3_cke
  io.ddr3.odt := mig.ddr3_odt
  io.ddr3.reset_n := mig.ddr3_reset_n
  io.ddr3.dm := mig.ddr3_dm

  mig.app_sr_req := false.B
  mig.app_ref_req := false.B
  mig.app_zq_req := false.B

  mig.app_addr := io.app_addr
  mig.app_cmd := io.app_cmd
  mig.app_en := io.app_en
  mig.app_wdf_data := io.app_wdf_data
  mig.app_wdf_wren := io.app_wdf_wren
  mig.app_wdf_end := io.app_wdf_end
  mig.app_wdf_mask := io.app_wdf_mask

  io.app_rdy := mig.app_rdy
  io.app_wdf_rdy := mig.app_wdf_rdy
  io.app_rd_data := mig.app_rd_data
  io.app_rd_data_valid := mig.app_rd_data_valid
  io.calib_done := mig.init_calib_complete
}
