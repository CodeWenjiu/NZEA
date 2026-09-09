package nzea_fpga.boards.lxb_artix7

import chisel3._

/** True Xilinx ILA IP blackbox — Vivado IP matches via desiredName.
  *
  * This is the module name (`u_ila_0`) that the ILA TCL snippet creates. It is
  * never instantiated directly by RTL; instead it lives inside [[IlaProbes]], the
  * synthesizable wrapper that firtool emits as its own file.
  *
  * The probe ports are named `probe0..probe21` (matching the Xilinx ILA IP
  * port names) with widths from [[IlaProbes.widths]]. The wrapper and the
  * blackbox share the same widths, so the RTL interface and the Vivado IP stay
  * in lockstep.
  */
class IlaProbesBlackbox extends ExtModule {
  override def desiredName = "u_ila_0"

  val clk = IO(Input(Clock()))
  val probe0 = IO(Input(IlaProbes.probeType(0)))
  val probe1 = IO(Input(IlaProbes.probeType(1)))
  val probe2 = IO(Input(IlaProbes.probeType(2)))
  val probe3 = IO(Input(IlaProbes.probeType(3)))
  val probe4 = IO(Input(IlaProbes.probeType(4)))
  val probe5 = IO(Input(IlaProbes.probeType(5)))
  val probe6 = IO(Input(IlaProbes.probeType(6)))
  val probe7 = IO(Input(IlaProbes.probeType(7)))
  val probe8 = IO(Input(IlaProbes.probeType(8)))
  val probe9 = IO(Input(IlaProbes.probeType(9)))
  val probe10 = IO(Input(IlaProbes.probeType(10)))
  val probe11 = IO(Input(IlaProbes.probeType(11)))
  val probe12 = IO(Input(IlaProbes.probeType(12)))
  val probe13 = IO(Input(IlaProbes.probeType(13)))
  val probe14 = IO(Input(IlaProbes.probeType(14)))
  val probe15 = IO(Input(IlaProbes.probeType(15)))
  val probe16 = IO(Input(IlaProbes.probeType(16)))
  val probe17 = IO(Input(IlaProbes.probeType(17)))
  val probe18 = IO(Input(IlaProbes.probeType(18)))
  val probe19 = IO(Input(IlaProbes.probeType(19)))
  val probe20 = IO(Input(IlaProbes.probeType(20)))
  val probe21 = IO(Input(IlaProbes.probeType(21)))
}

/** Synthesizable ILA wrapper, instantiated by [[LxbArtix7Core]] when `cfg.debugIla`.
  *
  * Board-level instrumentation: the tile stays ILA-agnostic and only exposes a
  * generic debug observation port ([[nzea_tile.platform.fpga.DebugIo]]); the
  * board layer decides to add the ILA and wires its probes from the tile
  * boundary signals.
  *
  * This is a plain `Module` (not an `ExtModule`), so firtool emits it as an
  * independent `IlaProbes.sv` file in the generated RTL. The Vivado project
  * generation creates the matching ILA IP from [[FpgaElaborate]]'s
  * `ila_config.json` (depth + widths), so RTL interface and IP stay in lockstep.
  *
  * Probe count is fixed (22: probe0..probe21), each bit width coming from
  * [[IlaProbes.widths]] — the single source of truth that the RTL wrapper, the
  * blackbox, and the generated ILA IP all derive from. The probe wiring lives
  * in [[LxbArtix7Core]] and must match this order.
  */
class IlaProbes extends Module {
  val probe0 = IO(Input(IlaProbes.probeType(0)))
  val probe1 = IO(Input(IlaProbes.probeType(1)))
  val probe2 = IO(Input(IlaProbes.probeType(2)))
  val probe3 = IO(Input(IlaProbes.probeType(3)))
  val probe4 = IO(Input(IlaProbes.probeType(4)))
  val probe5 = IO(Input(IlaProbes.probeType(5)))
  val probe6 = IO(Input(IlaProbes.probeType(6)))
  val probe7 = IO(Input(IlaProbes.probeType(7)))
  val probe8 = IO(Input(IlaProbes.probeType(8)))
  val probe9 = IO(Input(IlaProbes.probeType(9)))
  val probe10 = IO(Input(IlaProbes.probeType(10)))
  val probe11 = IO(Input(IlaProbes.probeType(11)))
  val probe12 = IO(Input(IlaProbes.probeType(12)))
  val probe13 = IO(Input(IlaProbes.probeType(13)))
  val probe14 = IO(Input(IlaProbes.probeType(14)))
  val probe15 = IO(Input(IlaProbes.probeType(15)))
  val probe16 = IO(Input(IlaProbes.probeType(16)))
  val probe17 = IO(Input(IlaProbes.probeType(17)))
  val probe18 = IO(Input(IlaProbes.probeType(18)))
  val probe19 = IO(Input(IlaProbes.probeType(19)))
  val probe20 = IO(Input(IlaProbes.probeType(20)))
  val probe21 = IO(Input(IlaProbes.probeType(21)))

  val impl = Module(new IlaProbesBlackbox)
  impl.clk := clock
  impl.probe0 := probe0
  impl.probe1 := probe1
  impl.probe2 := probe2
  impl.probe3 := probe3
  impl.probe4 := probe4
  impl.probe5 := probe5
  impl.probe6 := probe6
  impl.probe7 := probe7
  impl.probe8 := probe8
  impl.probe9 := probe9
  impl.probe10 := probe10
  impl.probe11 := probe11
  impl.probe12 := probe12
  impl.probe13 := probe13
  impl.probe14 := probe14
  impl.probe15 := probe15
  impl.probe16 := probe16
  impl.probe17 := probe17
  impl.probe18 := probe18
  impl.probe19 := probe19
  impl.probe20 := probe20
  impl.probe21 := probe21
}

object IlaProbes {
  /** ILA capture depth (samples per trigger).
    *
    * 16384 @ 100 MHz = 164 us — short, but this leaves BRAM budget for wide
    * MIG data-path probes (app_rd_data[31:0], app_wdf_data[31:0], ...).
    */
  val depth = 16384

  /** Probe widths, one entry per probe (probe0 .. probe21).
    *
    * Order must match the probe wiring in [[LxbArtix7Core]]:
    * probe0 cpu_reset, probe1 uart_txd, probe2 uart_rxd, probe3 finisher_finished,
    * probe4 bridge.flush, probe5 bridge.reqPending, probe6 bridge.respValid,
    * probe7 bridge.reqFire, probe8 bridge.uiState (3b), probe9 bridge.uiReqGoSeen,
    * probe10 bridge.uiDoneReq, probe11 bridge.uiAppEn,
    * probe12 mig ui_rst, probe13 mig calib_done, probe14 bridge.uiWen,
    * probe15 mig app_addr low 8 bits (8b), probe16 bridge.reqWen,
    * probe17 bridge.wdfWren, probe18 mig app_wdf_rdy, probe19 mig app_rdy,
    * probe20 mig app_rd_data_valid, probe21 mig app_rd_data[95:64] (32b).
    *
    * All 1-bit except probe8 (3b), probe15 (8b) and probe21 (32b). Widths are
    * kept minimal so the depth-16384 capture (@ 100 MHz ≈ 164 us) fits the
    * BRAM budget alongside the wide MIG data-path probes.
    */
  val widths: Seq[Int] = Seq(1, 1, 1, 1, 1, 1, 1, 1, 3, 1, 1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 1, 32)

  /** Chisel type for probe i: 1-bit → Bool, wider → UInt(widths(i).W). */
  def probeType(i: Int): Data =
    if (widths(i) == 1) Bool() else UInt(widths(i).W)
}