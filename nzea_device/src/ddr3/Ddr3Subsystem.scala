package nzea_device.ddr3

import chisel3._
import chisel3.experimental.{attach, Analog}

/** Debug observation port of [[Ddr3Subsystem]]: raw MIG reset/status signals
  * that are combined into [[MigUiIo.ui_rst]]. Left unconnected in plain usage
  * (Ddr3TestCore etc.); board ILA instrumentation reads it via LxbArtix7Core.
  */
class Ddr3SubsystemDebug extends Bundle {
  val ui_clk_sync_rst = Output(Bool())
  val ui_rst = Output(Bool()) // = ui_clk_sync_rst || !init_calib_complete
  val calib_done = Output(Bool())
}

/** DDR3 subsystem: wraps MigDdr3, exposes MIG native UI + physical pins.
  *
  * MMCM is external (in LxbArtix7Top); this module receives clk_200m directly.
  */
class Ddr3Subsystem extends Module {

  val io = IO(new Bundle {
    val rst_n = Input(Bool())
    val clk_200m = Input(Clock()) // MIG sys clock (MMCM in the board top)
    val mig = new MigUiIo
    val debug = Output(new Ddr3SubsystemDebug)
    // DDR3 control pins (the bidirectional databus is a separate Analog IO
    // group below — mixed bundles trip the firtool sink check in this
    // toolchain)
    val ddr3 = new Ddr3CtrlIo
  })
  val dq = IO(Analog(16.W))
  val dqs_p = IO(Analog(2.W))
  val dqs_n = IO(Analog(2.W))

  val mig = Module(new MigDdr3)
  mig.sys_clk_i := io.clk_200m
  // MIG sys_rst is active-LOW per mig_b.prj; io.rst_n is also active-LOW
  mig.sys_rst := io.rst_n

  // DDR3 physical pins — per-field at the BlackBox boundary (the MIG IP ports
  // are flat `ddr3_*` names; bulk connect cannot cross the naming mismatch).
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
  attach(dq, mig.ddr3_dq)
  attach(dqs_p, mig.ddr3_dqs_p)
  attach(dqs_n, mig.ddr3_dqs_n)

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
  io.mig.ui_clk := mig.ui_clk
  // Reset the ui-domain logic until calibration finishes (example-design
  // semantics: the application FSM stays in reset until init is complete).
  io.mig.ui_rst := mig.ui_clk_sync_rst || !mig.init_calib_complete

  // Debug observation (board ILA instrumentation).
  io.debug.ui_clk_sync_rst := mig.ui_clk_sync_rst
  io.debug.ui_rst := io.mig.ui_rst
  io.debug.calib_done := mig.init_calib_complete
}
