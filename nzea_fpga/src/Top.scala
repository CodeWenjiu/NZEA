package nzea_fpga

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuConfig, BpuStrategy, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_device.devices.{FinisherPins, UartPins}
import nzea_fpga.boards.lxb_artix7.{BoardClock, BoardXdc}
import nzea_fpga.device.uart.UartXdc
import nzea_fpga.ip.{ClockOut, Ila}
import nzea_fpga.device.ddr3.Ddr3Side
import nzea_fpga.logic.{Blinkers, BlinkersParameter, BootDdr, BootDdrParameter, DdrMemory, DdrMemoryParameter}
import nzea_fpga.primitives.{Bufg, BufgParameter}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{AddrRange, LiteBusRW, SlaveKind}
import nzea_rtl.probe.Probes
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import nzea_tile.{ExternalSpec, NzeaTile, NzeaTileParameter}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class TopParameter() extends Parameter

given upickle.default.ReadWriter[TopParameter] = upickle.default.macroRW

class TopLayers(parameter: TopParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The board's pins: the 50 MHz oscillator, the push button and the two LEDs (both active-low: the pin sinks the
  * current, so a zero lights the LED), and whatever the board's logics ask for ([[Top.pins]]).
  *
  * The names are the XDC's — the constraints bind by name, so they are the contract (the logics' own pin names are in
  * their files, `device/uart/UartXdc` for the serial line, the board's own four in
  * [[nzea_fpga.boards.lxb_artix7.BoardXdc]]).
  */
class TopIO(parameter: TopParameter) extends HWRecord(parameter):
    Flipped("CLK_50M", Clock())
    Aligned("LED1", Bool())
    Aligned("LED2", Bool())

    Pins.declare(this, Top.pins)

class TopProbe(parameter: TopParameter) extends DVRecord[TopParameter, TopLayers](parameter)

/** The board top: the clocking wizard, the reset network, the blinkers — the core the loader fills, and the loader
  * itself, whose pins it declares and wires ([[Logics]]).
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
    private val logics: Seq[BoardLogic] = Seq(DdrMemory, BootDdr)

    /** This level's own pins, plus the ones the logics asked for ([[Pins.declare]] holds the union): the button is the
      * reset network's reason and is asked for again by nothing else yet, and the serial line is the loader's own
      * receive direction plus the transmit pin the tile's UART drives through this level.
      */
    private[nzea_fpga] val pins: Seq[Pin] =
        Seq(Pin.in("RESET"), Pin.out(UartXdc.TxPin)) ++ logics.flatMap(_.needs.pins)

    /** The window the core runs in, as the tile's map names it, and the base the assembled programs are linked at
      * (`nzea_core/test/asm/build-elf.sh` links at this address, and the host tool is told it).
      */
    private val Window = "ram"
    private val Uart = "uart16550"
    private val MemoryBase = 0x80000000L
    // The device's whole address space: the window the core boots from and the loader writes is the memory, so it is
    // stated as large as the memory is (a memtest below the loader's frames is the next thing that will want it).
    private val MemorySize = 512L * 1024 * 1024

    /** The line rate the host speaks ([[probe]]'s `--baud`). */
    private val LineHz = 115200

    private val coreConfig = CoreConfig(Isa("riscv32i"), MemoryBase)

    /** The window this level answers itself: the RAM leaves the flow's hardware so the loader can write a program into
      * the board's DDR3 ([[DdrMemory]]). An external window is an increment — the tile shadows its own same-named
      * device — so this level states a window and never prunes a list.
      */
    private val externals = Seq(
        ExternalSpec(Window, AddrRange(BigInt(MemoryBase), BigInt(MemorySize)), SlaveKind.Memory, 1)
    )

    /** The core this top carries: no caches — the first build's margin is for the tile itself, which has never run on
      * this board — and the window answered from above.
      */
    private val tileParameter = NzeaTileParameter(
        config = coreConfig,
        flow = RtlFlow.Yosys,
        clockHz = (BootDdr.ClockGrid.mhz * 1000000).toInt,
        timeHz = 1000000,
        bypass = BypassLevel.Execute,
        bpu = Some(BpuConfig(BpuStrategy.Bimodal(64, 4), None)),
        lsqDepth = 4,
        icache = None,
        dcache = None,
        stats = StatScope.root,
        externals = externals
    )

    private val windowMap = NzeaTile.fabricMap(tileParameter)

    private val memoryParameter = DdrMemoryParameter(
        coreConfig.width,
        coreConfig.width,
        windowMap.userWidth,
        windowMap.idWidth,
        MemoryBase,
        2
    )

    private val bootParameter = BootDdrParameter(
        LineHz,
        coreConfig.width,
        coreConfig.width,
        windowMap.userWidth,
        windowMap.idWidth
    )

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

        // The board's program loader: the line's receiver, the boot state machine, and the hold it states — the core's
        // domain waits for that reason ([[Resets]]), so a load is what holds the core, not a wire to it.
        val boot = BootDdr.instantiate(bootParameter)

        val clkCore = Clocks.clock(BootDdr.ClockGrid)
        val clk100 = Clocks.clock(ClockOut(100.0, 0.0))

        // The memory the core boots from and the loader writes: the board's DDR3, behind the tile's window. Its own
        // reason — the controller is calibrated and the memory answers — is wired before the domains are stated, since
        // both of the domains below wait for it.
        val memory = DdrMemory.instantiate(memoryParameter)
        Logics.connect(io, memory.io, DdrMemory.needs)
        Logics.connect(io, boot.io, BootDdr.needs)

        // The one wizard, built once every grid has been asked for and before the logic whose reset chains wait on it.
        val clockingWizard = Clocks.materialize(BoardClock.oscillatorMHz, clk50Pin, !resetN50.asBool)
        val locked = clockingWizard.locked

        // The generator's lock, as a reason of its own: a domain the wizard feeds must not run before the clock it is
        // fed is settled. It exists only now — after the wizard is built — which is why a domain may wait for a reason
        // that is declared later, and why the network is wired once at the end ([[nzea_rtl.stdlib.Resets.materialize]]).
        Resets.term("pllLocked")(locked)

        val resetN100 = Resets.domain("board100", clk100, Seq("buttonPin", "pllLocked"))
        // The core waits for the loader's hold and for the memory itself: the fetches it makes come out of the DDR, and
        // the controller answers none of them before its calibration locked.
        val resetCore =
            Resets.domain("core", clkCore, Seq("buttonPin", "pllLocked", BootDdr.HoldNReason, Ddr3Side.ReadyReason))
        // The platform's own time: a load holds the core and the controller takes its time calibrating, and neither may
        // set the counter back — what it waits for is the board's reasons alone (`ClintParameter` holds the argument).
        val resetTime = Resets.domain("time", clkCore, Seq("buttonPin", "pllLocked"))

        Resets.materialize()

        // The core, on the loader's grid — and the memory its window is answered by, which the loader writes through.
        // A held core's side of the memory is drained there, which is why it is told the same hold level the network is.
        val tile = NzeaTile.instantiate(tileParameter)
        tile.io.field[Clock]("clock") := clkCore
        tile.io.field[Reset]("reset") := (!resetCore.asBool).asReset
        tile.io.field[Reset]("timeReset") := (!resetTime.asBool).asReset

        // The line the board's bridge talks to the fabric on: the loader listens on its receive pin (the pin the
        // constraints declare), and the tile's UART prints on the transmit pin this level wires out. One line, two
        // ends — the two must agree on its rate, which is why both are told the same number.
        UartXdc.register()
        io.field[Bool](UartXdc.TxPin) := tile.io.field[UartPins](Uart).txd

        // The memory the core fetches through and the loader writes: the tile's window on one side, the loader's port on
        // the other. The core's side of it is drained while the core is in reset there — the same reset the tile takes,
        // so every reason that can take the core away is one the memory accounts for, not only the loader's hold.
        memory.io.field[Clock]("nearClock") := clkCore
        memory.io.field[Reset]("nearResetN") := Resets.stated("boot")
        memory.io.field[Bool]("coreHeld") := !resetCore.asBool
        memory.io.field[LiteBusRW]("core") :<>= tile.io.field[LiteBusRW](Window)
        memory.io.field[LiteBusRW]("load") :<>= boot.io.field[LiteBusRW]("load")

        // One blinker per domain: a board where only LED1 blinks says the wizard's clock path is at fault. It takes the
        // two clocks and the two resets it needs, each the domain's own and already synchronized there.
        val blinkers = Blinkers.instantiate(BlinkersParameter(26, 0.1))
        blinkers.io.clock50 := clk50
        blinkers.io.resetN50 := resetN50
        led1 := blinkers.io.led1
        blinkers.io.clock100 := clk100
        blinkers.io.resetN100 := resetN100
        // LED2 answers the loaded program: steady once it has written the finisher, the 100 MHz blinker before that. The
        // panel owns both states — a steady LED is the same PWM at the panel's duty, not a pin tied low, which would jump
        // the LED to full brightness.
        val finished = tile.io.field[FinisherPins]("finisher").finished
        blinkers.io.finished := finished
        led2 := blinkers.io.led2

        // What is watched: the loader's own account of a load, the line's bytes (`BootDdr` binds the receiver's
        // declarations beside its instance), and the program's own verdict — the finisher's level.
        Probes.mark("finished")(finished)
