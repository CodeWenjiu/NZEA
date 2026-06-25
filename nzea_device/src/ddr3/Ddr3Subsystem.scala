package nzea_device.ddr3

import chisel3._
import chisel3.experimental.Analog

/** DDR3 subsystem: wraps MigDdr3, exposes MIG native UI + physical pins.
  *
  * MMCM is external (in LxbArtix7Top); this module receives clk_200m directly.
  */
class Ddr3Subsystem extends Module {

  val io = IO(new Bundle {
    val rst_n = Input(Bool())
    val mig = new MigUiIo
    val ddr3 = new Ddr3PhysIo
  })

  val mig = Module(new MigDdr3)
  mig.sys_clk_i := io.ddr3.clk_200m
  // MIG sys_rst is active-LOW per mig_b.prj; io.rst_n is also active-LOW
  mig.sys_rst := io.rst_n

  // DDR3 physical pins
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

  // MIG control
  mig.app_sr_req := false.B
  mig.app_ref_req := false.B
  mig.app_zq_req := false.B

  // MIG UI ↔ IO
  mig.app_addr := io.mig.app_addr
  mig.app_cmd := io.mig.app_cmd
  mig.app_en := io.mig.app_en
  mig.app_wdf_data := io.mig.app_wdf_data
  mig.app_wdf_wren := io.mig.app_wdf_wren
  mig.app_wdf_end := io.mig.app_wdf_end
  mig.app_wdf_mask := io.mig.app_wdf_mask

  io.mig.app_rdy := mig.app_rdy
  io.mig.app_wdf_rdy := mig.app_wdf_rdy
  io.mig.app_rd_data := mig.app_rd_data
  io.mig.app_rd_data_valid := mig.app_rd_data_valid
  io.mig.calib_done := mig.init_calib_complete
}
