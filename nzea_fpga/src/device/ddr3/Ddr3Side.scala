package nzea_fpga.device.ddr3

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Instance, Ref, Referable}
import me.jiuyang.zaozi.valuetpe.{Analog, Bool, Clock, Reset, UInt}
import nzea_fpga.ip.ClockOut
import nzea_fpga.{LogicNeeds, LogicReset, Pin}
import nzea_rtl.bus.LiteBusRW
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What the bus needs the level that carries it to hand in: the pins the level declares ([[Ddr3Side.needs]] is where
  * their names and directions are stated), the grids its instances run on, the reset of its own domain, and the port
  * the level declares the reason it produces on.
  */
case class Ddr3SideParameter(
    // The pins the bus drives through pads, and (the data and the strobes) the analog ends it attaches to.
    dq: Referable[Analog],
    dqsP: Referable[Analog],
    dqsN: Referable[Analog],
    ckP: Ref[Bool],
    ckN: Ref[Bool],
    addr: Ref[UInt],
    ba: Ref[UInt],
    dm: Ref[UInt],
    rasN: Ref[UInt],
    casN: Ref[UInt],
    weN: Ref[UInt],
    cke: Ref[Bool],
    odt: Ref[Bool],
    resetN: Ref[Bool],
    // The pin the tap calibration resets on, as the pin presents it: that primitive takes an asynchronous reset, and
    // the pin is what it is for that — the domain's own reset above cannot be it (its chain waits for the taps).
    rawResetN: Referable[Bool],
    // The domain's reset: what the level's network made of the reasons the domain waits for.
    reset: Ref[Reset],
    // The reason the device produces, on the port the level declares for it.
    tapsReady: Ref[Bool],
    // The grids, on the ports the level declared ([[nzea_fpga.Clocks.ports]]).
    clocks: Map[ClockOut, Ref[Clock]]
)

/** What the bus leaves for the level above: the controller instance (the netlist's name for it is what the simulators
  * reach into), the slave end of its bus — the port a master connects to, so the level wires its own master to the
  * device's own vocabulary rather than to a net — and the domain the bus runs in: its clock and its reset, so a master
  * runs on the controller's own grid and leaves reset with it.
  */
final case class Ddr3Bus(
    device: Instance[Ddr3IO, Ddr3Probe],
    bus: Ref[LiteBusRW],
    clock: Ref[Clock],
    resetN: Ref[Reset],
    ready: Ref[Bool]
)

/** The DDR3 device's side of the board, in one piece: the controller, its pads and the wires between them.
  *
  * What the device needs from the level above it is one statement, [[needs]]: the pins it drives and the pin it resets
  * on, the five grids its instances run on, the domain it runs in, and the reason it produces for the level's reset
  * network. The level declares the ports that statement asks for and connects them where it names the instance that
  * carries this device ([[nzea_fpga.Logics]]) — so the device's clocking and its reset are stated here, in its own
  * file, and the level writes no frequency, no phase and no reason of its own. Why ports rather than wires handed in: a
  * module is elaborated and written the moment it is instantiated, so what can cross into it afterwards is a port.
  *
  * One wizard is still the design's, built once by the level, because a phase relationship only exists between outputs
  * of one VCO — the memory's five grids are one such family.
  *
  * What is watched is the controller's own statement, made where the signals live (`Ddr3.scala`'s `Probes.mark` calls):
  * it is one instance, so one name is one net ([[Probes]]). A shared body's probes would be *declared* there and
  * *bound* here instead, where the instances are named — the shape `Blinkers` uses for the panel's two counters.
  */
object Ddr3Side:

    /** The pin the tap calibration resets on: the board's own button, as the pin presents it. */
    val ButtonPin = "RESET"

    /** The reason this device produces for the level's reset network: the taps are usable, so nothing about the device
      * was touched before it held.
      */
    val TapsReadyReason = "ddr3TapsReady"

    /** The second reason: the controller's own verdict that the memory answers — the bring-up's tap sweep locked and a
      * whole burst came back at those taps. A master that *reads* the memory (the core fetching from it) must wait for
      * this one, where a writer only needs the bus to take its requests.
      */
    val ReadyReason = "ddr3Ready"

    /** What the bus needs the level above to declare and wire ([[nzea_fpga.LogicNeeds]]): the pins, the grids, the
      * reason it produces, and its own domain — the board's button and the generator's lock are the level's reasons,
      * the taps' readiness its own.
      *
      * The grids are listed in the order the wizard's slots were named from, which is frozen: the IP built from this
      * plan and the constraints that name a grid by its slot are already on the board.
      *
      * The domain's terms are in the order its conjunction is built from: the two reasons the level's generated domains
      * share first, the device's own last — the same net the board's round ran against.
      */
    val needs: LogicNeeds = LogicNeeds(
        pins = Seq(
            Pin.in(ButtonPin),
            Pin.out(Ddr3PadNames.ckP),
            Pin.out(Ddr3PadNames.ckN),
            Pin.out(Ddr3PadNames.addr, Ddr3PadNames.addrWidth),
            Pin.out(Ddr3PadNames.ba, Ddr3PadNames.baWidth),
            Pin.out(Ddr3PadNames.dm, Ddr3PadNames.dmWidth),
            Pin.out(Ddr3PadNames.rasN, 1),
            Pin.out(Ddr3PadNames.casN, 1),
            Pin.out(Ddr3PadNames.weN, 1),
            Pin.out(Ddr3PadNames.cke),
            Pin.out(Ddr3PadNames.odt),
            Pin.out(Ddr3PadNames.resetN),
            Pin.pad(Ddr3PadNames.dq, Ddr3PadNames.dqWidth),
            Pin.pad(Ddr3PadNames.dqsP, Ddr3PadNames.dqsWidth),
            Pin.pad(Ddr3PadNames.dqsN, Ddr3PadNames.dqsWidth)
        ),
        grids = Seq(Ddr3Clocks.taps, Ddr3Clocks.loads, Ddr3Clocks.command, Ddr3Clocks.device, Ddr3Clocks.capture),
        produces = Seq(TapsReadyReason, ReadyReason),
        reset = Some(LogicReset("ddr3", Ddr3Clocks.loads, Seq("buttonPin", "pllLocked", TapsReadyReason)))
    )

    /** Build the bus: its calibrator, its controller and its pads, on the ports the level above declared ([[needs]]).
      *
      * Instance names are part of the netlist: the ones here are what the simulators reach into
      * (`nzea_fpga/sim/Ddr3Bringup.sv` watches `ddr3` and the pads by these names).
      */
    def instantiate(parameter: Ddr3SideParameter)(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        InstanceContext
    ): Ddr3Bus =
        // The bus's constraints travel with the bus: a level that instantiates it declares the pins the file binds, and
        // one that does not writes none of them ([[Ddr3Xdc]]).
        Ddr3Xdc.register()

        // The bank's tap calibration. Every lane in the design delays against it, and it is the one pad whose reset the
        // controller waits on: nothing about the device is touched before the taps are usable — the domain that waits
        // for its `READY` is the level's, stated from here ([[needs]]). Its own reset is the button as the pin presents
        // it: the primitive takes an asynchronous one, and the pin is what it is for that.
        val delayCtrl = Ddr3IdelayCtrl.instantiate(Ddr3IdelayCtrlParameter())
        delayCtrl.io.refClk := parameter.clocks(Ddr3Clocks.taps)
        delayCtrl.io.reset := !parameter.rawResetN
        parameter.tapsReady := delayCtrl.io.READY

        // What the pads' serializer shift paths are cleared by, in the pads' own polarity (active-low: the modules take
        // the level their `RST` pins are).
        val resetNddr = parameter.reset
        val ringReset = !resetNddr.asBool

        // The controller, on the pads' data grid: the serializers load their words from this domain's clock — it *is*
        // their `CLKDIV`'s clock — so the last register stage before every data pin is a same-clock path with a whole
        // cycle of budget. (On the 0° grid the load would sit a quarter period away and that stage misses setup by it:
        // measured at -1.1 ns on the placed design.) The commands' serializers stay on the 0° grid — 4.4 ns away from
        // this domain, which a command's decode closes easily — and the device's CK keeps the quarter period that
        // centres it in a command's window.
        val ddr3 = Ddr3.instantiate(Ddr3Parameter())
        ddr3.io.field[Clock]("clock") := parameter.clocks(Ddr3Clocks.loads)
        ddr3.io.field[Reset]("resetN") := resetNddr

        // The bus's pads. What the serializers' shift paths are cleared by is the bus's own domain reset: what clears a
        // shift path is what clears the registers whose word it loads, so the two sides come up in the same cycle.
        val dqPads = Ddr3DqPads.instantiate(Ddr3DqPadsParameter())
        dqPads.io.dqOut := ddr3.io.field[UInt](Ddr3PadNames.dqOut)
        dqPads.io.oe := ddr3.io.field[Bool](Ddr3PadNames.dqOe)
        dqPads.io.tap := ddr3.io.field[UInt](Ddr3PadNames.delayTap)
        dqPads.io.clk400 := parameter.clocks(Ddr3Clocks.command)
        dqPads.io.clk400Dqs := parameter.clocks(Ddr3Clocks.device)
        dqPads.io.clk200Dqs := parameter.clocks(Ddr3Clocks.loads)
        dqPads.io.clk200Inv := parameter.clocks(Ddr3Clocks.capture)
        dqPads.io.ringReset := ringReset
        ddr3.io.field[UInt](Ddr3PadNames.dqIn) := dqPads.io.dqIn
        parameter.dq.attach(dqPads.io.DQ)

        // The write strobes: they leave on the command grid, and the design never samples them back.
        val dqsPads = Ddr3DqsPads.instantiate(Ddr3DqsPadsParameter())
        dqsPads.io.dqsOut := ddr3.io.field[UInt](Ddr3PadNames.dqsOut)
        dqsPads.io.oe := ddr3.io.field[Bool](Ddr3PadNames.dqsOe)
        dqsPads.io.clk400 := parameter.clocks(Ddr3Clocks.command)
        dqsPads.io.clk200 := parameter.clocks(Ddr3Clocks.taps)
        dqsPads.io.ringReset := ringReset
        parameter.dqsP.attach(dqsPads.io.DQS_P)
        parameter.dqsN.attach(dqsPads.io.DQS_N)

        // The device's clock: the strobe grid, halved by a fixed 1/0 pattern, so its edges sit a quarter period into
        // the command's window.
        val ckPads = Ddr3CkPads.instantiate(Ddr3CkPadsParameter())
        ckPads.io.clk400Dqs := parameter.clocks(Ddr3Clocks.device)
        parameter.ckP := ckPads.io.CK_P
        parameter.ckN := ckPads.io.CK_N

        // The command/address side, the bank address and the three command pins: one module per pin group, at the width
        // the group has, and two levels per pin per cycle through every one of them. The mask pins are *not* one of
        // them: a byte strobe is a per-transfer fact, so the mask leaves on the data's own four-level grids
        // ([[Ddr3DmPads]]).
        val addrPads = Ddr3SdrPads.instantiate(Ddr3SdrPadsParameter(Ddr3PadNames.addrWidth))
        val baPads = Ddr3SdrPads.instantiate(Ddr3SdrPadsParameter(Ddr3PadNames.baWidth))
        val rasPads = Ddr3SdrPads.instantiate(Ddr3SdrPadsParameter(1))
        val casPads = Ddr3SdrPads.instantiate(Ddr3SdrPadsParameter(1))
        val wePads = Ddr3SdrPads.instantiate(Ddr3SdrPadsParameter(1))

        /** One single-rate pin group: the controller's levels in, the module's pins out to the level's own. */
        def wireSdrPads(pads: Instance[Ddr3SdrPadsIO, Ddr3SdrPadsProbe], pin: String, pad: Ref[UInt]): Unit =
            pads.io.levels := ddr3.io.field[UInt](pin)
            pads.io.clk400 := parameter.clocks(Ddr3Clocks.command)
            pads.io.clk200 := parameter.clocks(Ddr3Clocks.taps)
            pads.io.ringReset := ringReset
            pad := pads.io.pad

        wireSdrPads(addrPads, Ddr3PadNames.addr, parameter.addr)
        wireSdrPads(baPads, Ddr3PadNames.ba, parameter.ba)
        wireSdrPads(rasPads, Ddr3PadNames.rasN, parameter.rasN)
        wireSdrPads(casPads, Ddr3PadNames.casN, parameter.casN)
        wireSdrPads(wePads, Ddr3PadNames.weN, parameter.weN)

        // The mask pins: the data's own grids, because the mask has to keep step with the transfers it masks.
        val dmPads = Ddr3DmPads.instantiate(Ddr3DmPadsParameter())
        dmPads.io.levels := ddr3.io.field[UInt](Ddr3PadNames.dm)
        dmPads.io.clk400Dqs := parameter.clocks(Ddr3Clocks.device)
        dmPads.io.clk200Dqs := parameter.clocks(Ddr3Clocks.loads)
        dmPads.io.ringReset := ringReset
        parameter.dm := dmPads.io.pad

        // The housekeeping pins: levels that change once per command at most, registered on the data grid.
        val ckePads = Ddr3LevelPads.instantiate(Ddr3LevelPadsParameter())
        val odtPads = Ddr3LevelPads.instantiate(Ddr3LevelPadsParameter())
        val resetNPads = Ddr3LevelPads.instantiate(Ddr3LevelPadsParameter())
        for (pads, level, pin) <- Seq(
                (ckePads, ddr3.io.field[Bool](Ddr3PadNames.cke), parameter.cke),
                (odtPads, ddr3.io.field[Bool](Ddr3PadNames.odt), parameter.odt),
                (resetNPads, ddr3.io.field[Bool](Ddr3PadNames.resetN), parameter.resetN)
            )
        do
            pads.io.level := level
            pads.io.clk400 := parameter.clocks(Ddr3Clocks.command)
            pin := pads.io.pad

        // What the level wires next: the controller, the slave end of the bus a master connects to, the domain the
        // bus runs in, and the controller's own readiness (the level states it as a reason).
        Ddr3Bus(
            ddr3,
            ddr3.io.field[LiteBusRW]("bus"),
            parameter.clocks(Ddr3Clocks.loads),
            resetNddr,
            ddr3.io.field[Bool]("calibrated")
        )
