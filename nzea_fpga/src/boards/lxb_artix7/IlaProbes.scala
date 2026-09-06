package nzea_fpga.boards.lxb_artix7

import chisel3._

/** True Xilinx ILA IP blackbox — Vivado IP matches via desiredName.
  *
  * This is the module name (`u_ila_0`) that the ILA TCL snippet creates. It is
  * never instantiated directly by RTL; instead it lives inside [[IlaProbes]], the
  * synthesizable wrapper that firtool emits as its own file.
  *
  * The probe ports are named `probe0..probe7` (matching the Xilinx ILA IP port
  * names) with widths from [[IlaProbes.widths]]. The wrapper and the blackbox
  * share the same widths, so the RTL interface and the Vivado IP stay in lockstep.
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
  * Probe count is fixed (8: probe0..probe7), each bit width coming from
  * [[IlaProbes.widths]] — the single source of truth that the RTL wrapper, the
  * blackbox, and the generated ILA IP all derive from. The probe wiring lives in
  * [[LxbArtix7Core]] and must match this order.
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
}

object IlaProbes {
  /** ILA capture depth (samples per trigger).
    *
    * 131072 @ 100 MHz = 1.31 ms, enough to span the whole uart-reset command
    * (~1.04 ms) so one window covers pre-reset state, the reset pulse, the
    * release and the first post-reset cycles of the program.
    */
  val depth = 131072

  /** Probe widths, one entry per probe (probe0 .. probe7).
    *
    * Order must match the probe wiring in [[LxbArtix7Core]]:
    * probe0 cpu_reset (via TileIo debug port), probe1 uart_txd, probe2 uart_rxd,
    * probe3 finisher_finished, probe4 ram_req_valid, probe5 ram_resp_valid,
    * probe6 ram_boot_valid, probe7 ram_req_addr.
    */
  val widths: Seq[Int] = Seq(1, 1, 1, 1, 1, 1, 1, 32)

  /** Chisel type for probe i: 1-bit → Bool, wider → UInt(widths(i).W). */
  def probeType(i: Int): Data =
    if (widths(i) == 1) Bool() else UInt(widths(i).W)
}