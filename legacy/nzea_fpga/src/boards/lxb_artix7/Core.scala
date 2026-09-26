package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.experimental.{attach, Analog}
import chisel3.util._
import nzea_config.core.CoreConfig
import nzea_device.ddr3.{Ddr3Adapter, Ddr3CtrlIo, Ddr3Subsystem}
import nzea_device.ram.RamFabricSlave
import nzea_tile.NzeaTile

/** A7-Lite FPGA core logic — NzeaTile + internal SRAM (boot) + DDR3 (device bus). */
class LxbArtix7Core(cfg: LxbArtix7Config)(implicit config: CoreConfig) extends Module {
  private val addrW = config.width
  private val dataW = config.width

  val io = IO(new Bundle {
    val uart_tx = Output(Bool())
    val uart_rx = Input(Bool())
    val led_alive = Output(Bool())
    val led_finish = Output(Bool())
    val clk_200m = Input(Clock()) // MIG sys clock (from the top MMCM)
    // DDR3 control pins — bidirectional databus is separate below
    val ddr3 = new Ddr3CtrlIo
  })

  val dq = IO(Analog(16.W))
  val dqs_p = IO(Analog(2.W))
  val dqs_n = IO(Analog(2.W))

  val tile = Module(new NzeaTile(cfg.tile))

  val tileIo = tile.io.asInstanceOf[nzea_tile.platform.fpga.TileIo]

  // Derive bus widths from the tile IO type, not hardcoded constants.
  private val userW = tileIo.extRamBus(0).userWidth
  private val idW = tileIo.extRamBus(0).idWidth

  io.uart_tx := tileIo.fpga_uart.txd
  tileIo.fpga_uart.rxd := io.uart_rx
  tileIo.fpga_uart.ctsn := false.B

  val ram = Module(new RamFabricSlave(addrW, dataW, userW, idW, baseAddr = 0x80000000L))
  // External memory bus 0 = boot SRAM (AddressMap.ram).
  ram.io.bus <> tileIo.extRamBus(0)
  ram.io.boot <> tileIo.extRamBoot
  tileIo.extRamCalibDone := true.B // SRAM, always ready

  // ── DDR3 device: MIG subsystem + LiteBus adapter ───────────────
  val ddr = Module(new Ddr3Subsystem)
  ddr.io.rst_n := !reset.asBool // MIG sys_rst is active-low
  ddr.io.clk_200m := io.clk_200m
  io.ddr3 := ddr.io.ddr3
  attach(dq, ddr.dq)
  attach(dqs_p, ddr.dqs_p)
  attach(dqs_n, ddr.dqs_n)

  val bridge = Module(new Ddr3Adapter(addrW, dataW, userW, idW, baseAddr = BigInt("88000000", 16), wrDrainCycles = 32))
  // External memory bus 1 = board DDR3 (AddressMap.ddr) via the MIG adapter.
  bridge.io.bus <> tileIo.extRamBus(1)
  bridge.io.mig <> ddr.io.mig
  // ui domain must follow the tile reset (cpuReset / boot load / uart-reset):
  // the adapter's own module reset is the board power-on reset (coreRst) and
  // does NOT cover uart-reset, so without this a mid-flight ui transaction
  // survives a load and its stale completion corrupts the next program's
  // first accesses (reproduced in the xsim cosim: writes land one slot late).
  bridge.io.tileReset := tileIo.debug.cpuReset

  val blinkCnt = RegInit(0.U(26.W))
  blinkCnt := blinkCnt + 1.U
  io.led_alive := blinkCnt(25)
  io.led_finish := tileIo.fpga_finish

  // ILA debug wrapper — board-level instrumentation. Probe order must match
  // IlaProbes.widths (single source of truth for the Vivado IP). cpu_reset
  // comes through the tile's generic DebugIo observation port.
  //
  // After the DDR3 bring-up, the ILA watches only the MIG physical layer +
  // board IO — the adapter protocol is covered by cosim and the unit tests,
  // so the 11 adapter-internal debug probes (bridge.io.debug.*) were dropped
  // (they also let the capture depth go up if needed).
  if (cfg.debugIla) {
    val ila = Module(new IlaProbes)
    ila.probe0 := tileIo.debug.cpuReset
    ila.probe1 := tileIo.fpga_uart.txd
    ila.probe2 := tileIo.fpga_uart.rxd
    ila.probe3 := tileIo.fpga_finish
    // MIG status: ui-domain reset and calibration complete. ui_rst is the
    // adapter's ui-domain reset (calib + tile reset); calib_done gates request
    // admission. If calib never completes, ui_rst stays high and the write
    // path deadlocks.
    ila.probe4 := ddr.io.mig.ui_rst
    ila.probe5 := ddr.io.debug.calib_done
    // app_addr actually driven to the MIG (row<<4 | half<<3) — low 8 bits.
    // Debugging the row-shift: reads of 0x88000040 returning 0x88000010 data
    // mean the MIG sees a shifted address.
    ila.probe6 := ddr.io.mig.app_addr(7, 0)
    // MIG write/read handshake state.
    ila.probe7 := ddr.io.mig.app_wdf_rdy
    ila.probe8 := ddr.io.mig.app_rdy
    ila.probe9 := ddr.io.mig.app_rd_data_valid
    // Read data actually returned by the MIG (high 64-bit half, low 32 bits
    // of it = app_rd_data[95:64]) — the physical-layer read data.
    ila.probe10 := ddr.io.mig.app_rd_data(95, 64)
  }

}
