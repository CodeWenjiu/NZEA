package nzea_fpga.boards.lxb_artix7

import chisel3._

/** True Xilinx ILA IP blackbox — Vivado IP matches via desiredName.
  *
  * This is the module name (`u_ila_0`) that the ILA TCL snippet creates. It is
  * never instantiated directly by RTL; instead it lives inside [[IlaProbes]], the
  * synthesizable wrapper that firtool emits as its own file.
  *
  * The probe ports are named `probe0..probe10` (matching the Xilinx ILA IP port
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
  val probe8 = IO(Input(IlaProbes.probeType(8)))
  val probe9 = IO(Input(IlaProbes.probeType(9)))
  val probe10 = IO(Input(IlaProbes.probeType(10)))
}

/** Synthesizable ILA wrapper — instantiate this in the board top when debugging.
  *
  * This is a plain `Module` (not an `ExtModule`), so firtool emits it as an
  * independent `IlaProbes.sv` file in the generated RTL. The Vivado project
  * generation detects ILA usage by the *presence of that file*: instantiating
  * this wrapper is the single source of truth that both (a) connects the probes
  * in RTL and (b) makes the ILA IP get created in the Vivado project.
  *
  * Probe count is fixed (11: probe0..probe10) but each probe's bit width comes
  * from [[IlaProbes.widths]]. 1-bit probes are `Bool`, wider probes are `UInt`.
  * Edit `IlaProbes.widths` to match the signals you want to observe — it is the
  * single source of truth that the RTL wrapper, the blackbox, and the generated
  * ILA IP all derive from.
  *
  * Instantiate it only when debugging; when the design does not reference it,
  * firtool drops the module and no `IlaProbes.sv` is emitted, so no ILA IP is
  * generated.
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
}

object IlaProbes {
  /** ILA capture depth (samples per trigger). */
  val depth = 4096

  /** Probe widths, one entry per probe (probe0 .. probe10).
    *
    * This is the single source of truth for the probe bit widths: the RTL
    * wrapper ports, the blackbox, and the generated ILA IP all derive from it.
    * Change it to match the signals you want to observe.
    */
  val widths: Seq[Int] = Seq(1, 1, 1, 1, 1, 3, 29, 32, 4, 1, 5)

  /** Chisel type for probe i: 1-bit → Bool, wider → UInt(widths(i).W). */
  def probeType(i: Int): Data =
    if (widths(i) == 1) Bool() else UInt(widths(i).W)
}
