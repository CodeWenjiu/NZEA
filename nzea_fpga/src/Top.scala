package nzea_fpga

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_fpga.debug.Ila
import nzea_fpga.ip.{ClockPlan, ClockingWizard, ClockingWizardParameter}
import nzea_fpga.primitives.{Bufg, BufgParameter}
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What the board top is built as. Empty for now: the board has no build knobs, and instrumentation is not one — a
  * bring-up board exists to be watched, so the ILA is part of the design.
  */
case class TopParameter() extends Parameter

given upickle.default.ReadWriter[TopParameter] = upickle.default.macroRW

class TopLayers(parameter: TopParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The board's pins: the 50 MHz oscillator, the push button (active-low), and the two LEDs (active-low: the pin sinks
  * the current, so a zero lights the LED). The names are the XDC's — the constraints bind by name, so they are part of
  * the contract.
  */
class TopIO(parameter: TopParameter) extends HWRecord(parameter):
    Flipped("CLK_50M", Clock())
    Flipped("RESET", Bool())
    Aligned("LED1", Bool())
    Aligned("LED2", Bool())

class TopProbe(parameter: TopParameter) extends DVRecord[TopParameter, TopLayers](parameter)

/** The fpga level's top: a bring-up design — the clocking wizard and two blinkers, one per clock domain — plus the
  * level's IP paths.
  *
  * Nothing here is board-specific but the pin names, which the constraints (the xdc files under `boards/<board>/`) bind
  * by name — a board whose pins differ forks this file when it needs to.
  *
  * Two blinkers on purpose: LED1 is driven from the 50 MHz oscillator directly, LED2 from the wizard's 100 MHz output,
  * so a board where only LED1 blinks says the wizard's clock path (or its IP) is at fault. The button is asynchronous
  * and active-low: [[ResetSync]] de-asserts it synchronously, and the wizard's `locked` holds the 100 MHz domain in
  * reset until the clock it produces is settled.
  *
  * The top is wiring only — every register lives in a module that takes its own clock and reset ports (a `ResetScope`
  * is built from a port), so what a level top does is distribute clocks and resets and connect pins.
  *
  * A level like any other: what it instantiates (a tile, a DDR subsystem) lands here as the board grows.
  */
@generator
object Top extends Generator[TopParameter, TopLayers, TopIO, TopProbe]:
    override def moduleName(parameter: TopParameter): String = "Top"

    def architecture(parameter: TopParameter) =
        val io = summon[Interface[TopIO]]
        val clk50Pin = io.field[Clock]("CLK_50M")
        val rawResetN = io.field[Bool]("RESET")
        val led1 = io.field[Bool]("LED1")
        val led2 = io.field[Bool]("LED2")

        // The oscillator as the fabric sees it: through the device's global buffer. The pin itself feeds it, and the
        // clock generator below takes the pin as it is — its input path is what its own compensation is about, and a
        // generator fed through the tool's buffer on the same net fails the IO-clock DRC (a PLL: `REQP-1712`).
        val clk50Buffer = Bufg.instantiate(BufgParameter())
        clk50Buffer.io.I := clk50Pin
        val clk50 = clk50Buffer.io.O

        // The board's instrumentation: what the design watches is declared where the signal lives (`Probes` — the
        // declarations are unconditional, there is no table to open first), and the ILA is this level's consumer of
        // that table. It is a debug core the netlist flow creates, not an RTL instance.
        //
        // Not the probe mechanism (`nzea_rtl.probe`): it lowers to SystemVerilog `bind`, and Vivado *synthesis*
        // silently drops `bind` (verified: the bound module never reaches the netlist, and the ILA core disappears from
        // the build without a warning). What does reach it is `mark_debug`: a declaration keeps a wire named after the
        // signal and marks it for the netlist debug flow, which connects the ILA core to those nets after synthesis
        // ([[Ila]] writes the script; `create_debug_core` only exists on the synthesized netlist). A declaration
        // can sit beside the signal it declares at any depth — no port threading — which is the point of the mechanism.
        val ila = Ila("u_ila_0", 1024)
        ila.clock(clk50)

        // The reset chain: the button is asynchronous and active-low, and the synchronizer hands on an active-low reset
        // (a `Reset` whose zero is the asserted state, which is the polarity every module below declares).
        val sync = ResetSync.instantiate(ResetSyncParameter())
        sync.io.clock := clk50
        sync.io.rawN := rawResetN.asReset
        val resetN = sync.io.syncN

        // The 50 MHz domain: the first blinker, straight off the oscillator.
        val blink50 = Blink.instantiate(BlinkParameter(26))
        blink50.io.clock := clk50
        blink50.io.resetN := resetN
        led1 := blink50.io.led

        // The board's clocking: the plan is the board's fact — its oscillator in, what the design wants out — and the
        // IP (its ports, its configuration) is derived from it (`nzea_fpga.ip.ClockingWizard`).
        val clockingWizard = ClockingWizard.instantiate(
            ClockingWizardParameter("clk_wiz_0", ClockPlan(50.0, Seq(200.0, 100.0)))
        )
        clockingWizard.io.field[Clock]("clk_in1") := clk50Pin
        clockingWizard.io.field[Bool]("reset") := !resetN.asBool
        val locked = clockingWizard.io.field[Bool]("locked")
        val clk100 = clockingWizard.io.field[Clock]("clk_out2")

        // The wizard's domain: the second blinker, held in reset while the button is pressed or the clock is unsettled.
        val blink100 = Blink.instantiate(BlinkParameter(26))
        blink100.io.clock := clk100
        blink100.io.resetN := (resetN.asBool | !locked).asReset
        led2 := blink100.io.led

        // What is watched: the top's own observables, marked where it holds them, and the blinkers' counters,
        // declared beside the counters (`Blink`) and bound to the instances that matter — the binding is stated where
        // the instance is named, which a shared module body cannot know for itself. Each probe's width is the signal's
        // own, so the core's probe list, the debug script, the rename map and the capture script all come from these
        // declarations.
        Probes.mark("reset")(!resetN.asBool)
        Probes.mark("locked")(locked)
        Probes.mark("led1")(led1)
        Probes.mark("led2")(led2)
        Probes.watch(blink50, "count")
        Probes.watch(blink100, "count")
