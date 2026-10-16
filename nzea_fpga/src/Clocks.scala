package nzea_fpga

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Ref, Referable, Wire}
import me.jiuyang.zaozi.valuetpe.{Bool, Clock}
import nzea_fpga.ip.{ClockOut, ClockPlan, ClockPrimitive, ClockingWizard, ClockingWizardParameter}
import nzea_rtl.DumpRegistry
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.mutable

/** The design's clock needs, and the one wizard that serves them: a design asks for a grid by stating what it is —
  * `Clocks.clock(Ddr3Clocks.device)` is "the grid the memory's data and strobes leave on (400 MHz, a quarter period
  * after the command side's)" — and the requests are what the plan, the IP and the constraints are derived from. There
  * is no separate table of the clocks a design uses: the set of clocks *is* the set of requests.
  *
  * There are two ways to ask, and they differ only in who writes the connection:
  *
  *   - [[clock]] hands the caller the wire the grid lands on: the caller wires it where it is used (a level's own
  *     logic, a port it is driving itself);
  *   - [[bind]] hands nothing back: the caller states that an *instance's* port runs on that grid, and the connection
  *     is made when the wizard is built. That is what lets a device keep its own clocking — the file that names an
  *     instance's ports is free to state, right there, which grid each runs on, and the level above writes no clock at
  *     all ([[nzea_fpga.device.ddr3.Ddr3Side]]).
  *
  * The wizard itself is built later ([[materialize]]) and once, because a wizard's port list *is* its plan (`clk_out1`
  * first) and a plan is only complete when the last caller has asked. The wire in between is what makes both possible,
  * and it is what makes the same key the same grid: the second caller of a key gets the first caller's wire, so "400
  * MHz at 0°" asked by the command pads and by the capture registers is one net, one clock, one domain — merging is not
  * a policy here, it is what the key means.
  *
  * One wizard, not one per caller, is also a *correctness* statement rather than a saving: what has to hold between two
  * grids is sometimes their phase, and a phase relationship only exists between outputs of one VCO. The memory's family
  * is the loud case ([[nzea_fpga.device.ddr3.Ddr3Pads]]: the data and the strobes a quarter period off the command
  * side, the load clocks on the shift clocks' edges, the CK's own quarter period) — two generators would put an unknown
  * phase between those grids, which is what the design states the relationship to avoid.
  *
  * A grid the board's oscillator already is (today the 50 MHz itself) is *not* served from here: the buffered pin is
  * the design's own net ([[nzea_fpga.Top]]), and a wizard output at the input's rate would be a second net at the same
  * frequency — a second clock domain wearing the first one's name. `materialize` says so instead of building it.
  *
  * What derives from the requests: the IP's configuration and port list ([[ClockingWizardParameter]] — the same list
  * the tcl is written from, so the IP and the RTL cannot disagree), and the *names* Vivado's own constraints give each
  * grid ([[outputName]]), which is what a constraint has to spell to name one. No constraint of this design names a
  * grid today: the crossings that needed an exception were removed with the shared reset level they were about
  * ([[nzea_fpga.boards.lxb_artix7.BoardXdc]] says so), and a grid named any other way — by a slot — would be bound to a
  * fact of the plan's order rather than to what the grid *is*.
  *
  * Not built yet: more than one wizard. A plan past what one primitive serves fails loudly here rather than being split
  * quietly into two generators (which would be exactly the phase relationship above, broken).
  */
object Clocks:

    /** The wizard's IP instance name: one wizard, so one name. */
    val ipName = "clk_wiz_0"

    /** The grids the design asked for, in first-ask order (a bind asks as well): the plan, `clk_out1` first. Keyed by
      * the grid itself — `(frequency, phase)` — because that, not a name, is what a caller knows about a clock.
      */
    private val plan = mutable.LinkedHashSet.empty[ClockOut]

    /** The wires [[clock]] handed its callers: the grids whose connection the caller writes itself. */
    private val wires = mutable.LinkedHashMap.empty[ClockOut, Wire[Clock]]

    /** What [[bind]] left to the mechanism: `(an instance's port, the grid it runs on)`, connected when the wizard is
      * built. The port belongs to the block that names the instance — the block `materialize` runs in.
      */
    private val bound = mutable.ArrayBuffer.empty[(Ref[Clock], ClockOut)]

    /** Whether a wizard has been built: a second one would drive the same wires twice. */
    private var materialized = false

    DumpRegistry.onReset: () =>
        plan.clear()
        wires.clear()
        bound.clear()
        materialized = false

    /** Ask for a grid and take the wire it lands on, for a caller writing the connection itself. The same key is the
      * same wire, whoever asks and however often.
      */
    def clock(grid: ClockOut)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Wire[Clock] =
        plan.add(grid)
        wires.getOrElseUpdate(grid, Wire(Clock()))

    /** Ask for a grid *for an instance's port*: the connection is not the caller's to write, so the mechanism writes it
      * ([[materialize]]). What the caller states is the whole of it — this port runs on that grid.
      *
      * An instance's ports are named where the instance is named, so the file that names them can state its own
      * clocking there: a device may bind its controller's and its pads' ports to its own grids and the level above it
      * writes no clock at all. Nothing here needs the elaboration context — a binding is a statement until it is
      * performed.
      */
    def bind(port: Ref[Clock], grid: ClockOut): Unit =
        plan.add(grid)
        bound += ((port, grid))

    /** The plan's outputs, `clk_out1` first — what the wizard is built from and what [[outputName]] names. */
    def planned: Seq[ClockOut] = plan.toSeq

    /** What a constraint has to spell to name a grid: the clock the wizard's IP creates for it (`clk_out1` is the
      * plan's first output). Asking is what earns the name — a constraint for a grid this design never asked for is a
      * mistake in the text, and it fails here rather than binding nothing in Vivado.
      */
    def outputName(grid: ClockOut): String =
        val slot = planned.indexOf(grid)
        require(
            slot >= 0,
            s"a constraint names a clock this design did not ask for: $grid (asked for: ${planned.mkString(", ")})"
        )
        s"clk_out${slot + 1}_$ipName"

    /** Build the wizard from the requests, and make every connection that was asked for: the wires [[clock]] handed
      * out, and the instance ports [[bind]] stated. Called once — [[nzea_fpga.Top]] does, after everything that wants a
      * grid has asked for one — and returns the wizard's `locked`, which the domains that come up behind it wait on.
      */
    def materialize(inputMHz: Double, input: Referable[Clock], reset: Referable[Bool])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Wizard =
        require(!materialized, "the design's wizard is built once: a second one would drive the same grids twice")
        val outputs = planned
        require(outputs.nonEmpty, "a wizard with no clocks asked for: the design asked for nothing")
        require(
            !outputs.exists(_.mhz == inputMHz),
            s"a clock at the oscillator's own $inputMHz MHz: the buffered pin is that clock " +
                "(nzea_fpga.Top), and a wizard output at the input's rate would be a second net at it"
        )
        val plan = ClockPlan(inputMHz, outputs)
        require(
            plan.outputs.sizeIs <= plan.primitive.outputs,
            s"${plan.outputs.size} clocks asked for; one ${plan.primitive.name} serves ${plan.primitive.outputs} " +
                "(a second wizard is not built yet)"
        )
        require(
            plan.outputs.forall(_.phase == 0.0) || ClockPrimitive.mmcmSolution(plan).isDefined,
            s"the phases asked for are not on the MMCM's fine phase grid (1/${ClockPrimitive.PhaseSteps} of a VCO " +
                s"period): ${plan.outputs.map(out => s"${out.mhz}@${out.phase}").mkString(", ")}"
        )
        val wizard = ClockingWizard.instantiate(ClockingWizardParameter(ipName, plan))
        wizard.io.field[Clock]("clk_in1") := input
        wizard.io.field[Bool]("reset") := reset
        val grid = plan.outputs.zipWithIndex
            .map((out, slot) => out -> wizard.io.field[Clock](s"clk_out${slot + 1}"))
            .toMap
        for (out, wire) <- wires do wire := grid(out)
        for (port, out) <- bound do port := grid(out)
        materialized = true
        Wizard(wizard.io.field[Bool]("locked"))

    /** What a materialized wizard hands the design: the `locked` its consumers' reset chains wait on. */
    case class Wizard(locked: Ref[Bool])
