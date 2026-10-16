package nzea_fpga

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_fpga.boards.lxb_artix7.{BoardClock, BoardXdc}
import nzea_fpga.device.ddr3.{Ddr3Bus, Ddr3PadNames, Ddr3Side, Ddr3SideParameter}
import nzea_fpga.ip.{ClockOut, Ila}
import nzea_fpga.logic.{Blinkers, BlinkersParameter, Ddr3Soak, Ddr3SoakParameter}
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

/** The board's pins: the 50 MHz oscillator, the push button (active-low), the two LEDs (active-low: the pin sinks the
  * current, so a zero lights the LED), and the DDR3 bus's pads.
  *
  * The names are the XDC's — the constraints bind by name, so they are the contract
  * ([[nzea_fpga.device.ddr3.Ddr3PadNames]] holds the bus's, and the board's own four are in
  * [[nzea_fpga.boards.lxb_artix7.BoardXdc]]).
  *
  * The DDR3 pads are this module's *own* ports: the data and the strobes are `inout` — the device drives them on a read
  * — which the DSL states with `Analog`, and the modules that hold the I/O cells' primitives are instantiated beside
  * them and attached to them ([[nzea_fpga.device.ddr3.Ddr3Pads]]). Nothing above this module knows the bus exists.
  */
class TopIO(parameter: TopParameter) extends HWRecord(parameter):
    Flipped("CLK_50M", Clock())
    Flipped("RESET", Bool())
    Aligned("LED1", Bool())
    Aligned("LED2", Bool())

    // The device's clock, the command/address side and the housekeeping pins: plain outputs, one serializer (or one
    // register) per pin behind an output buffer.
    Aligned(Ddr3PadNames.ckP, Bool())
    Aligned(Ddr3PadNames.ckN, Bool())
    Aligned(Ddr3PadNames.addr, UInt(Ddr3PadNames.addrWidth))
    Aligned(Ddr3PadNames.ba, UInt(Ddr3PadNames.baWidth))
    Aligned(Ddr3PadNames.dm, UInt(Ddr3PadNames.dmWidth))
    Aligned(Ddr3PadNames.rasN, UInt(1))
    Aligned(Ddr3PadNames.casN, UInt(1))
    Aligned(Ddr3PadNames.weN, UInt(1))
    Aligned(Ddr3PadNames.cke, Bool())
    Aligned(Ddr3PadNames.odt, Bool())
    Aligned(Ddr3PadNames.resetN, Bool())

    // The data and the strobes: both ends drive them, so they are `inout` — the device's own drivers meet this design's
    // tristating pads on one net ([[nzea_fpga.device.ddr3.Ddr3Pads]] holds the buffers).
    Aligned(Ddr3PadNames.dq, Analog(Ddr3PadNames.dqWidth))
    Aligned(Ddr3PadNames.dqsP, Analog(Ddr3PadNames.dqsWidth))
    Aligned(Ddr3PadNames.dqsN, Analog(Ddr3PadNames.dqsWidth))

class TopProbe(parameter: TopParameter) extends DVRecord[TopParameter, TopLayers](parameter)

/** The board top: the clocking wizard, the blinkers, the DDR3 controller and the pads it drives.
  *
  * It *is* the top — the module the constraints bind, the project's `--top` and the design the simulators instantiate:
  * every pin the board has is a port of this module, the pads' primitives included ([[nzea_fpga.device.ddr3.Ddr3Pads]],
  * which is where the DDR3 bus's I/O cells live). There is no ring around it and no second top above it.
  *
  * Nothing here is board-specific but the pin names: the constraints bind by name, and the level's own pins' text is
  * the board's file ([[nzea_fpga.boards.lxb_artix7.BoardXdc]]) — a board whose pins differ forks this file and that
  * one.
  *
  * The LEDs are the bring-up panel ([[nzea_fpga.logic.Blinkers]]): two blinkers, one per domain, so a board where only
  * LED1 blinks says the wizard's clock path (or its IP) is at fault. The DDR3 bus ([[nzea_fpga.device.ddr3.Ddr3Side]])
  * is put through this level's own campaign ([[nzea_fpga.logic.Ddr3Soak]]): the level wires the bus's campaign side to
  * the soak and nothing else — what a round carries, what it answers and what the campaign accounts are the two
  * modules' own business. The button is asynchronous and active-low, and the resets are a network of *reasons*, not one
  * signal ([[nzea_rtl.stdlib.Resets]]): each domain states the reasons it waits for where its own instances are named,
  * and takes them through its own synchronizer — so this level's pins and the generator's lock reach every domain that
  * needs them, and no other.
  *
  * The clocking the design runs on is the design's own statement ([[nzea_fpga.Clocks]]): each grid is asked for where
  * it is needed, the wizard that serves them all is derived from those asks — one VCO, so the grids that must keep a
  * phase relationship do — and the only board fact in it is the oscillator's rate
  * ([[nzea_fpga.boards.lxb_artix7.BoardClock]]).
  *
  * The top is wiring only — every register lives in a module that takes its own clock and reset ports (a `ResetScope`
  * is built from a port) — so what a level top does is distribute clocks and resets and connect pins.
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

        // The level's own pins are constrained by the board's own file; the bus's file travels with the bus — it is
        // registered where the bus is built ([[nzea_fpga.device.ddr3.Ddr3Side]]), so a level that instantiates no DDR3
        // writes none of its constraints.
        BoardXdc.register()

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
        //
        // The core runs on the oscillator's own buffered clock, which is also why that net is not one of the clocks
        // asked for below ([[Clocks]] would refuse the request): its edges line up with the fabric's instead of sitting
        // a quarter period off them, so the counters it watches come across as they are.
        val ila = Ila("u_ila_0", 1024)
        ila.clock(clk50)

        // ── The reset network ([[nzea_rtl.stdlib.Resets]]) ──
        //
        // The button as the pin presents it is the reason every domain starts from, and it is *raw*: that is what a pin
        // is, and each domain's own synchronizer is what makes its release belong to that domain. The oscillator's
        // domain has a chain because it has flops of its own (the LED panel, the debug core's clock), not because the
        // others borrow its level — a level cleaned in one domain and handed to another would be a crossing we would
        // then have to say something about.
        Resets.term("buttonPin")(rawResetN)
        val resetN50 = Resets.domain("board50", clk50, Seq("buttonPin"))

        // The board's DDR3 bus, in one piece: its controller, its pads, its calibrator, its grids and its own reset
        // chain are one device-side unit ([[nzea_fpga.device.ddr3.Ddr3Side]]), which asks for the grids it runs on where
        // its own instances are named ([[nzea_fpga.device.ddr3.Ddr3Clocks]]), declares the reasons it waits for, and
        // states its own domain's reset there ([[nzea_rtl.stdlib.Resets]]). The pads are the level's pin ring all the
        // same — they drive the level's own ports and attach to its `inout` pads — but nothing of the bus's internals is
        // this file's to know, its reset policy included.
        val ddr3Bus = Ddr3Side.instantiate(
            Ddr3SideParameter(
                dq = io.field[Analog](Ddr3PadNames.dq),
                dqsP = io.field[Analog](Ddr3PadNames.dqsP),
                dqsN = io.field[Analog](Ddr3PadNames.dqsN),
                ckP = io.field[Bool](Ddr3PadNames.ckP),
                ckN = io.field[Bool](Ddr3PadNames.ckN),
                addr = io.field[UInt](Ddr3PadNames.addr),
                ba = io.field[UInt](Ddr3PadNames.ba),
                dm = io.field[UInt](Ddr3PadNames.dm),
                rasN = io.field[UInt](Ddr3PadNames.rasN),
                casN = io.field[UInt](Ddr3PadNames.casN),
                weN = io.field[UInt](Ddr3PadNames.weN),
                cke = io.field[Bool](Ddr3PadNames.cke),
                odt = io.field[Bool](Ddr3PadNames.odt),
                resetN = io.field[Bool](Ddr3PadNames.resetN),
                rawResetN = rawResetN
            )
        )

        // What the bus is put through: this level's own campaign ([[nzea_fpga.logic.Ddr3Soak]]), wired to the bus's
        // campaign side where both instances are named. It is *this* level's logic — the device knows nothing of it,
        // and the bus hands over what it needs to be one ([[Ddr3Bus]]): the domain to run in (the controller's own
        // grid and its reset, so a round's verdict arrives where it is stamped) and the ports a round carries and
        // answers. A level that wires no campaign writes none of these lines — the bus keeps its own sequence
        // (init, refresh, the round's timing is its own) and its campaign ports are simply left open.
        val soak = Ddr3Soak.instantiate(Ddr3SoakParameter())
        soak.io.field[Clock]("clock") := ddr3Bus.clock
        soak.io.field[Reset]("resetN") := ddr3Bus.resetN
        ddr3Bus.wireCampaign(soak)

        // This level's own grid: the second blinker's. The key is the grid itself, (frequency, phase), and the same key
        // is the same wire, so asking twice is asking once ([[Clocks]]).
        val clk100 = Clocks.clock(ClockOut(100.0, 0.0))

        // The wizard that serves every grid asked for — the bus's five and this one: one plan, one VCO, built here —
        // once, when every grid has been asked for, and before the logic whose reset chains wait on it. A generator per
        // requester would be a phase relationship lost between the grids that need one; its input is the pin itself,
        // because a generator fed through the tool's own buffer on that net fails the IO-clock DRC (`REQP-1712`).
        val clockingWizard = Clocks.materialize(BoardClock.oscillatorMHz, clk50Pin, !resetN50.asBool)
        val locked = clockingWizard.locked

        // The generator's lock, as a reason of its own: a domain the wizard feeds must not run before the clock it is fed
        // is settled. It exists only now — after the wizard is built — which is why a domain may wait for a reason that
        // is declared later, and why the network is wired once at the end ([[nzea_rtl.stdlib.Resets.materialize]]).
        Resets.term("pllLocked")(locked)

        // This level's own 100 MHz domain: the button's pin and the generator's lock, and nothing else — the
        // oscillator's own domain does not wait for a lock it does not need, which is the whole point of each domain
        // stating its own set.
        val resetN100 = Resets.domain("board100", clk100, Seq("buttonPin", "pllLocked"))

        // Every reason declared and every domain stated: wire each domain's conjunction into the synchronizer it owns.
        // The bus's domain was stated inside [[nzea_fpga.device.ddr3.Ddr3Side]] and waits for the same two reasons plus
        // its own taps-ready.
        Resets.materialize()

        // The board's LEDs: the bring-up panel — one blinker per domain, so the domain whose counter stands still is
        // the domain whose clocking is at fault ([[nzea_fpga.logic.Blinkers]]). It takes the two clocks and the two
        // resets it needs, and nothing else: each is the domain's own, already synchronized there. The duty (0.25) is a
        // comfortable level for bring-up blinkers; 1 is the plain full-on toggle.
        val blinkers = Blinkers.instantiate(BlinkersParameter(26, 0.25))
        blinkers.io.clock50 := clk50
        blinkers.io.resetN50 := resetN50
        led1 := blinkers.io.led1
        blinkers.io.clock100 := clk100
        blinkers.io.resetN100 := resetN100
        led2 := blinkers.io.led2

        // What is watched: the top's own observables, marked where it holds them (the bus binds its own, beside its
        // instances — a shared module body cannot know which instance a probe should mean). Each probe's width is the
        // signal's own, so the core's probe list, the debug script, the rename map and the capture script all come from
        // these declarations. The reset network's own observations are its own: every reason is marked where it is
        // declared, and every domain where it is stated ([[nzea_rtl.stdlib.Resets]]).
        Probes.mark("led1")(led1)
        Probes.mark("led2")(led2)
