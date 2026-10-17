package nzea_fpga

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_fpga.boards.lxb_artix7.{BoardClock, BoardXdc}
import nzea_fpga.ip.{ClockOut, Ila}
import nzea_fpga.logic.{Blinkers, BlinkersParameter, Ddr3Soak, Ddr3SoakParameter}
import nzea_fpga.primitives.{Bufg, BufgParameter}
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class TopParameter() extends Parameter

given upickle.default.ReadWriter[TopParameter] = upickle.default.macroRW

class TopLayers(parameter: TopParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The board's pins: the 50 MHz oscillator, the push button and the two LEDs (both active-low: the pin sinks the
  * current, so a zero lights the LED), and the pads the board's chips ask for ([[Top.pins]]).
  *
  * The names are the XDC's — the constraints bind by name, so they are the contract
  * ([[nzea_fpga.device.ddr3.Ddr3PadNames]] holds the DDR3 bus's, the board's own four are in
  * [[nzea_fpga.boards.lxb_artix7.BoardXdc]]).
  *
  * The data and the strobes are this module's *own* `inout` pads — the device drives them on a read too — which the DSL
  * states with `Analog`; the modules that hold the I/O cells' primitives are instantiated by the logic that owns the
  * chip ([[nzea_fpga.logic.Ddr3Soak]]). Nothing above this module knows the bus exists.
  */
class TopIO(parameter: TopParameter) extends HWRecord(parameter):
    Flipped("CLK_50M", Clock())
    Aligned("LED1", Bool())
    Aligned("LED2", Bool())

    Pins.declare(this, Top.pins)

class TopProbe(parameter: TopParameter) extends DVRecord[TopParameter, TopLayers](parameter)

/** The board top: the clocking wizard, the reset network, the blinkers — and the logics it carries, whose pins it
  * declares and wires ([[Logics]]).
  *
  * It *is* the top — the module the constraints bind, the project's `--top` and the design the simulators instantiate:
  * every pin the board has is a port of this module, the pads' primitives included, wherever in the hierarchy they
  * land. Nothing here is board-specific but the pin names ([[nzea_fpga.boards.lxb_artix7.BoardXdc]]) — a board whose
  * pins differ forks this file and that one.
  *
  * The top is wiring only — every register lives in a module that takes its own clock and reset ports (a `ResetScope`
  * is built from a port) — so what a level top does is distribute clocks and resets and connect pins.
  */
@generator
object Top extends Generator[TopParameter, TopLayers, TopIO, TopProbe]:
    override def moduleName(parameter: TopParameter): String = "Top"

    /** The logics this top carries ([[BoardLogic]]), in the order their instances are named in [[architecture]]. */
    private val logics: Seq[BoardLogic] = Seq(Ddr3Soak)

    /** This level's own pins, plus the ones the logics asked for ([[Pins.declare]] holds the union): the button is the
      * reset network's reason and the device's tap calibration resets on the same pin, so it is stated here as the
      * level's own and asked for again by the device — identical requests are one port.
      */
    private[nzea_fpga] val pins: Seq[Pin] = Seq(Pin.in("RESET")) ++ logics.flatMap(_.needs.pins)

    def architecture(parameter: TopParameter) =
        val io = summon[Interface[TopIO]]
        val clk50Pin = io.field[Clock]("CLK_50M")
        val rawResetN = io.field[Bool]("RESET")
        val led1 = io.field[Bool]("LED1")
        val led2 = io.field[Bool]("LED2")

        BoardXdc.register()

        // The oscillator through the device's global buffer, so what the fabric sees is a global net and not a wire.
        // The wizard below is fed the pin itself: a generator whose input path goes through the tool's own buffer on
        // that net fails the IO-clock DRC (a PLL: `REQP-1712`).
        val clk50Buffer = Bufg.instantiate(BufgParameter())
        clk50Buffer.io.I := clk50Pin
        val clk50 = clk50Buffer.io.O

        // The board's instrumentation: declarations are made where the signals live (`Probes` — unconditional, there is
        // no table to open first), and the ILA is this level's consumer of that table. The core is a netlist artifact,
        // not an RTL instance: `create_debug_core` only exists on the synthesized netlist ([[Ila]] writes the script),
        // and the RTL's half is `mark_debug` — a declaration keeps a wire named after the signal and marks it for the
        // netlist debug flow. The probe mechanism's own lowering (`nzea_rtl.probe`) is SystemVerilog `bind`, which
        // Vivado *synthesis* silently drops (verified: the bound module never reaches the netlist and the core vanishes
        // from the build without a warning).
        //
        // The core runs on the oscillator's own buffered clock, which is why that net is not one of the clocks asked
        // for below ([[Clocks]] would refuse it): its edges line up with the fabric's instead of sitting a quarter
        // period off them, so the counters it watches come across as they are.
        val ila = Ila("u_ila_0", 1024)
        ila.clock(clk50)

        // The button as the pin presents it is raw, and each domain's own synchronizer is what makes its release
        // belong to that domain — a level cleaned in one domain and handed to another would be a crossing.
        Resets.term("buttonPin")(rawResetN)
        val resetN50 = Resets.domain("board50", clk50, Seq("buttonPin"))

        val soak = Ddr3Soak.instantiate(Ddr3SoakParameter())
        Logics.connect(io, soak.io, Ddr3Soak.needs)

        val clk100 = Clocks.clock(ClockOut(100.0, 0.0))

        // The one wizard, built once every grid has been asked for and before the logic whose reset chains wait on it.
        val clockingWizard = Clocks.materialize(BoardClock.oscillatorMHz, clk50Pin, !resetN50.asBool)
        val locked = clockingWizard.locked

        // The generator's lock, as a reason of its own: a domain the wizard feeds must not run before the clock it is
        // fed is settled. It exists only now — after the wizard is built — which is why a domain may wait for a reason
        // that is declared later, and why the network is wired once at the end ([[nzea_rtl.stdlib.Resets.materialize]]).
        Resets.term("pllLocked")(locked)

        val resetN100 = Resets.domain("board100", clk100, Seq("buttonPin", "pllLocked"))

        Resets.materialize()

        // One blinker per domain: a board where only LED1 blinks says the wizard's clock path is at fault. It takes the
        // two clocks and the two resets it needs, each the domain's own and already synchronized there.
        val blinkers = Blinkers.instantiate(BlinkersParameter(26, 0.25))
        blinkers.io.clock50 := clk50
        blinkers.io.resetN50 := resetN50
        led1 := blinkers.io.led1
        blinkers.io.clock100 := clk100
        blinkers.io.resetN100 := resetN100
        led2 := blinkers.io.led2

        // What is watched: the top's own observables, marked where it holds them (the bus binds its own beside its
        // instances, since a shared module body cannot know which instance a probe should mean). The core's probe list,
        // the debug script, the rename map and the capture script all come from these declarations.
        Probes.mark("led1")(led1)
        Probes.mark("led2")(led2)
