package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.util._
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig
import nzea_device.ddr3.{Ddr3Adapter, Ddr3PhysIo, Ddr3Subsystem}
import nzea_tile.NzeaTile

/** A7-Lite FPGA core logic — instantiates NzeaTile + DDR3 subsystem.
  *
  * DDR3 is a board-level concern; the tile only exposes generic external RAM ports. This module bridges the tile to the
  * DDR3 MIG IP.
  *
  *   - LED1: ~1.5 Hz blink (alive indicator)
  *   - LED2: finisher triggered
  */
class LxbArtix7Core(clockHz: Int)(implicit config: CoreConfig) extends Module {
  private val addrW = config.width
  private val dataW = config.width
  private val userW = 64
  private val idW = 8

  val io = IO(new Bundle {
    val uart_tx = Output(Bool())
    val uart_rx = Input(Bool())
    val led_alive = Output(Bool())
    val led_finish = Output(Bool())
    val ddr3 = new Ddr3PhysIo
  })

  val tile = Module(new NzeaTile(sim = false, platform = SynthPlatform.HelloFPGA, clockHz = clockHz))
  val tileIo = tile.io.asInstanceOf[nzea_tile.platform.hellofpga.TileIo]

  io.uart_tx := tileIo.fpga_uart.txd
  tileIo.fpga_uart.rxd := io.uart_rx
  tileIo.fpga_uart.ctsn := false.B

  // ── DDR3 subsystem (board-level, not in tile) ────────────────
  val adapter = Module(new Ddr3Adapter(addrW, dataW, userW, idW))
  val ddr3Sub = Module(new Ddr3Subsystem(addrW, dataW, userW, idW))

  // DDR3 physical pins (subsystem ↔ board IO directly, tile unaware)
  ddr3Sub.io.ddr3.clk_200m := io.ddr3.clk_200m
  ddr3Sub.io.rst_n := !reset.asBool

  // Tile external RAM bus → adapter → DDR3
  // req: tile(master) drives out, adapter(slave) receives in
  adapter.io.bus.req.valid := tileIo.extRamBus.req.valid
  adapter.io.bus.req.bits := tileIo.extRamBus.req.bits
  tileIo.extRamBus.req.ready := adapter.io.bus.req.ready
  tileIo.extRamBus.req.flush := adapter.io.bus.req.flush
  // resp: adapter(slave) drives out, tile(master) receives in
  tileIo.extRamBus.resp.valid := adapter.io.bus.resp.valid
  tileIo.extRamBus.resp.bits := adapter.io.bus.resp.bits
  adapter.io.bus.resp.ready := tileIo.extRamBus.resp.ready
  adapter.io.bus.resp.flush := tileIo.extRamBus.resp.flush
  adapter.io.boot <> tileIo.extRamBoot
  tileIo.extRamCalibDone := ddr3Sub.io.calib_done

  // Adapter ↔ Subsystem (MIG native UI)
  ddr3Sub.io.app_addr := adapter.io.app_addr
  ddr3Sub.io.app_cmd := adapter.io.app_cmd
  ddr3Sub.io.app_en := adapter.io.app_en
  ddr3Sub.io.app_wdf_data := adapter.io.app_wdf_data
  ddr3Sub.io.app_wdf_wren := adapter.io.app_wdf_wren
  ddr3Sub.io.app_wdf_end := adapter.io.app_wdf_end
  ddr3Sub.io.app_wdf_mask := adapter.io.app_wdf_mask
  adapter.io.app_rdy := ddr3Sub.io.app_rdy
  adapter.io.app_wdf_rdy := ddr3Sub.io.app_wdf_rdy
  adapter.io.app_rd_data := ddr3Sub.io.app_rd_data
  adapter.io.app_rd_data_valid := ddr3Sub.io.app_rd_data_valid
  adapter.io.calib_done := ddr3Sub.io.calib_done

  // DDR3 physical pins (subsystem ↔ board IO directly)
  io.ddr3.dq <> ddr3Sub.io.ddr3.dq
  io.ddr3.dqs_p <> ddr3Sub.io.ddr3.dqs_p
  io.ddr3.dqs_n <> ddr3Sub.io.ddr3.dqs_n
  io.ddr3.addr := ddr3Sub.io.ddr3.addr
  io.ddr3.ba := ddr3Sub.io.ddr3.ba
  io.ddr3.ras_n := ddr3Sub.io.ddr3.ras_n
  io.ddr3.cas_n := ddr3Sub.io.ddr3.cas_n
  io.ddr3.we_n := ddr3Sub.io.ddr3.we_n
  io.ddr3.ck_p := ddr3Sub.io.ddr3.ck_p
  io.ddr3.ck_n := ddr3Sub.io.ddr3.ck_n
  io.ddr3.cke := ddr3Sub.io.ddr3.cke
  io.ddr3.odt := ddr3Sub.io.ddr3.odt
  io.ddr3.reset_n := ddr3Sub.io.ddr3.reset_n
  io.ddr3.dm := ddr3Sub.io.ddr3.dm

  // ── LEDs ───────────────────────────────────────────────────
  val blinkCnt = RegInit(0.U(26.W))
  blinkCnt := blinkCnt + 1.U
  io.led_alive := blinkCnt(25) // ~1.5 Hz
  io.led_finish := tileIo.fpga_finish
}
