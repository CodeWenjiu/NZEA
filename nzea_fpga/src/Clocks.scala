package nzea_fpga

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Interface, Ref, Referable, Wire}
import me.jiuyang.zaozi.valuetpe.{Bool, Clock, Record}
import nzea_fpga.ip.{ClockOut, ClockPlan, ClockPrimitive, ClockingWizard, ClockingWizardParameter}
import nzea_rtl.DumpRegistry
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.mutable

/** The design's clock requests, and the one wizard that serves them: the requests *are* the plan (the set of clocks a
  * design uses is the set of asks), and the plan's order is the wizard's port order (`clk_out1` first), so
  * [[materialize]] runs once, after the last caller has asked. A grid the board's oscillator already is (the 50 MHz
  * itself) is refused there rather than built as a second net at the same frequency.
  *
  * One wizard, not one per caller: a phase relationship only exists between outputs of one VCO, so two generators would
  * put an unknown phase between the grids that must keep one. A plan past what one primitive serves fails loudly here
  * instead of being split.
  *
  * [[clock]] hands the caller the wire the grid lands on. A logic that is a module of its own cannot be handed a wire
  * after it exists — its clocks are its ports — so it states the grids its instances run on in its needs, and the level
  * above asks for those grids and connects them where it names the instance ([[nzea_fpga.Logics]]).
  */
object Clocks:

    /** The wizard's IP instance name: the tcl and the constraints' clock names spell it. */
    val ipName = "clk_wiz_0"

    /** The grids the design asked for, in first-ask order — the plan, `clk_out1` first. Keyed by the grid itself,
      * `(frequency, phase)`, which is what a caller knows about a clock.
      */
    private val plan = mutable.LinkedHashSet.empty[ClockOut]

    /** The wires [[clock]] handed its callers: the grids whose connection the caller writes itself. */
    private val wires = mutable.LinkedHashMap.empty[ClockOut, Wire[Clock]]

    private var materialized = false

    DumpRegistry.onReset: () =>
        plan.clear()
        wires.clear()
        materialized = false

    /** Ask for a grid and take the wire it lands on, for a caller writing the connection itself: the same key is the
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

    /** What a grid's port is named on a logic's own side ([[nzea_fpga.Logics]]): the grid said out loud, so a reader —
      * or a waveform — can tell which clock a port carries without knowing the plan.
      */
    def portName(grid: ClockOut): String = s"clk${spelled(grid.mhz)}_${spelled(grid.phase)}"

    private def spelled(value: Double): String =
        if value.isWhole then value.toLong.toString else value.toString

    /** Declare a clock port per grid — how a logic that is a module of its own takes the grids its instances run on.
      * Called in the record's body, which is what its port list is built from.
      */
    def declare(record: Record, grids: Seq[ClockOut]): Unit =
        for grid <- grids do record.Flipped(portName(grid), Clock())

    /** Ask for a grid *for a logic's own port*: the logic's clocks cross the boundary as ports, so the level above asks
      * for the wire here and connects it where it names the logic's instance — [[materialize]] fills the wire when it
      * builds the wizard. The requests are made in this order, which is what names the wizard's slots (`clk_out1`
      * first): a plan whose slots are already built into a bitstream and into constraints keeps them.
      */
    def connect(logic: Interface[?], grids: Seq[ClockOut])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit =
        for grid <- grids do logic.field[Clock](portName(grid)) := clock(grid)

    /** The grids as a logic's own ports hold them: what a device file reads to clock the instances it owns, keyed by
      * the grid each port carries.
      */
    def ports(io: Interface[?], grids: Seq[ClockOut]): Map[ClockOut, Ref[Clock]] =
        grids.map(grid => grid -> io.field[Clock](portName(grid))).toMap

    /** The plan's outputs, `clk_out1` first — what the wizard is built from and what [[outputName]] names. */
    def planned: Seq[ClockOut] = plan.toSeq

    /** What a constraint has to spell to name a grid: the clock the wizard's IP creates for it (`clk_out1` is the
      * plan's first output). A constraint for a grid the design never asked for fails here rather than binding nothing
      * in Vivado.
      */
    def outputName(grid: ClockOut): String =
        val slot = planned.indexOf(grid)
        require(
            slot >= 0,
            s"a constraint names a clock this design did not ask for: $grid (asked for: ${planned.mkString(", ")})"
        )
        s"clk_out${slot + 1}_$ipName"

    /** Build the wizard from the requests, and make every connection that was asked for: the wires [[clock]] handed
      * out. Called once ([[nzea_fpga.Top]] does, after everything that wants a grid has asked for one); returns the
      * wizard's `locked`, which the domains behind it wait on.
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
        materialized = true
        Wizard(wizard.io.field[Bool]("locked"))

    /** What a materialized wizard hands the design: the `locked` its consumers' reset chains wait on. */
    case class Wizard(locked: Ref[Bool])
