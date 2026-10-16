package nzea_fpga.device.ddr3

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Instance, Ref, Referable}
import me.jiuyang.zaozi.valuetpe.{Analog, Bool, Clock, Reset, UInt}
import nzea_fpga.Clocks
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.Resets
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What the bus needs from the level that carries it: the pins the level declares ([[nzea_fpga.TopIO]]), which are
  * where the pads' analog ends land and what the pins the bus drives are. Nothing else — the grids are the bus's own
  * statement ([[Ddr3Clocks]]) and the wiring is [[Ddr3Side]]'s.
  *
  * `rawResetN` is the button as the pin presents it, separately from the level's own synchronized chain: the tap
  * calibration's reset is asynchronous and the pin is what it is for that.
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
    rawResetN: Referable[Bool]
)

/** What the bus leaves for the level above: the controller instance (the netlist's name for it is what the simulators
  * reach into), the slave end of its bus — the port a master connects to, so the level wires its own master to the
  * device's own vocabulary rather than to a net — and the domain the bus runs in: its clock and its reset, so a master
  * runs on the controller's own grid and leaves reset with it.
  */
final case class Ddr3Bus(
    device: Instance[Ddr3IO, Ddr3Probe],
    bus: Ref[LiteBusRW],
    clock: Referable[Clock],
    resetN: Referable[Reset]
)

/** The DDR3 device's side of the board, in one piece: the controller, its pads, its grids and its reset, wired where
  * its own instances are named.
  *
  * This file is what "a device keeps its own clocking — and its own reset" looks like. The pads' and the controller's
  * clock ports are bound to [[Ddr3Clocks]]' grids *here*, next to the instances they belong to ([[Clocks.bind]]), so
  * the level above writes no clock, knows no frequency and holds no grid. The reset is the device's own statement too
  * ([[Resets]]): it declares the reasons it waits for — the level's vocabulary (the released button, the generator's
  * lock) and its own fact (the taps' readiness) — and states its domain's reset here, on its own grid, through its own
  * synchronizer. What the level hands in is the pins it declares, and nothing else ([[Ddr3SideParameter]]). The one
  * wizard that serves every grid is still the design's, built once by the level ([[Clocks.materialize]]), because a
  * phase relationship between grids only exists between outputs of one VCO — the memory's five grids are one such
  * family; the reset network is wired once by the level for the same shape of reason ([[Resets.materialize]]).
  *
  * What is watched is the controller's own statement, made where the signals live (`Ddr3.scala`'s `Probes.mark` calls):
  * it is one instance, so one name is one net ([[Probes]]). A shared body's probes would be *declared* there and
  * *bound* here instead, where the instances are named — the shape `Blinkers` uses for the panel's two counters.
  */
object Ddr3Side:

    /** Build the bus: its pads, its controller, its calibrator, its own reset chain — and its grids, bound to it.
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
        // controller waits on: nothing about the device is touched before the taps are usable. Its reset is the button
        // as the pin presents it — the primitive takes an asynchronous one, and the pin is what it is for that.
        val delayCtrl = Ddr3IdelayCtrl.instantiate(Ddr3IdelayCtrlParameter())
        Clocks.bind(delayCtrl.io.refClk, Ddr3Clocks.taps)
        delayCtrl.io.reset := !parameter.rawResetN
        val delayReady = delayCtrl.io.READY

        // The bus's own reasons, and the reset its domain runs on. What the device must wait for is its own statement:
        // the board's released button and the generator's lock are the level's vocabulary, the taps' readiness is its
        // own fact — and the synchronizer that makes their release belong to the bus's domain is created here, on the
        // bus's own grid ([[Resets]]). Its two flops are also what filters the levels' own short excursions. Its clock
        // is the controller's: one synchronizer, in the domain that consumes it.
        Resets.term("ddr3TapsReady")(delayReady)
        val ddr3Clock = Clocks.clock(Ddr3Clocks.loads)
        val resetNddr = Resets.domain("ddr3", ddr3Clock, Seq("buttonPin", "pllLocked", "ddr3TapsReady"))
        // What the pads' serializer shift paths are cleared by, in the pads' own polarity (active-low: the modules take
        // the level their `RST` pins are).
        val ringReset = !resetNddr.asBool

        // The controller, on the pads' data grid: the serializers load their words from this domain's clock — it *is*
        // their `CLKDIV`'s clock — so the last register stage before every data pin is a same-clock path with a whole
        // cycle of budget. (On the 0° grid the load would sit a quarter period away and that stage misses setup by it:
        // measured at -1.1 ns on the placed design.) The commands' serializers stay on the 0° grid — 4.4 ns away from
        // this domain, which a command's decode closes easily — and the device's CK keeps the quarter period that
        // centres it in a command's window.
        val ddr3 = Ddr3.instantiate(Ddr3Parameter())
        Clocks.bind(ddr3.io.field[Clock]("clock"), Ddr3Clocks.loads)
        ddr3.io.field[Reset]("resetN") := resetNddr

        // The bus's pads. What the serializers' shift paths are cleared by is the bus's own domain reset: what clears a
        // shift path is what clears the registers whose word it loads, so the two sides come up in the same cycle.
        val dqPads = Ddr3DqPads.instantiate(Ddr3DqPadsParameter())
        dqPads.io.dqOut := ddr3.io.field[UInt](Ddr3PadNames.dqOut)
        dqPads.io.oe := ddr3.io.field[Bool](Ddr3PadNames.dqOe)
        dqPads.io.tap := ddr3.io.field[UInt](Ddr3PadNames.delayTap)
        Clocks.bind(dqPads.io.clk400, Ddr3Clocks.command)
        Clocks.bind(dqPads.io.clk400Dqs, Ddr3Clocks.device)
        Clocks.bind(dqPads.io.clk200Dqs, Ddr3Clocks.loads)
        Clocks.bind(dqPads.io.clk200Inv, Ddr3Clocks.capture)
        dqPads.io.ringReset := ringReset
        ddr3.io.field[UInt](Ddr3PadNames.dqIn) := dqPads.io.dqIn
        parameter.dq.attach(dqPads.io.DQ)

        // The write strobes: they leave on the command grid, and the design never samples them back.
        val dqsPads = Ddr3DqsPads.instantiate(Ddr3DqsPadsParameter())
        dqsPads.io.dqsOut := ddr3.io.field[UInt](Ddr3PadNames.dqsOut)
        dqsPads.io.oe := ddr3.io.field[Bool](Ddr3PadNames.dqsOe)
        Clocks.bind(dqsPads.io.clk400, Ddr3Clocks.command)
        Clocks.bind(dqsPads.io.clk200, Ddr3Clocks.taps)
        dqsPads.io.ringReset := ringReset
        parameter.dqsP.attach(dqsPads.io.DQS_P)
        parameter.dqsN.attach(dqsPads.io.DQS_N)

        // The device's clock: the strobe grid, halved by a fixed 1/0 pattern, so its edges sit a quarter period into
        // the command's window.
        val ckPads = Ddr3CkPads.instantiate(Ddr3CkPadsParameter())
        Clocks.bind(ckPads.io.clk400Dqs, Ddr3Clocks.device)
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
            Clocks.bind(pads.io.clk400, Ddr3Clocks.command)
            Clocks.bind(pads.io.clk200, Ddr3Clocks.taps)
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
        Clocks.bind(dmPads.io.clk400Dqs, Ddr3Clocks.device)
        Clocks.bind(dmPads.io.clk200Dqs, Ddr3Clocks.loads)
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
            Clocks.bind(pads.io.clk400, Ddr3Clocks.command)
            pin := pads.io.pad

        // What the level wires next: the controller, the slave end of the bus a master connects to, and the domain the
        // bus runs in.
        Ddr3Bus(ddr3, ddr3.io.field[LiteBusRW]("bus"), ddr3Clock, resetNddr)
