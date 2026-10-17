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

/** The design's clock requests, and the one wizard that serves them: the requests *are* the plan (the set of clocks a
  * design uses is the set of asks), and the plan's order is the wizard's port order (`clk_out1` first), so
  * [[materialize]] runs once, after the last caller has asked. A grid the board's oscillator already is (the 50 MHz
  * itself) is refused there rather than built as a second net at the same frequency.
  *
  * One wizard, not one per caller: a phase relationship only exists between outputs of one VCO, so two generators would
  * put an unknown phase between the grids that must keep one. A plan past what one primitive serves fails loudly here
  * instead of being split.
  *
  * [[clock]] hands the caller the wire the grid lands on; [[bind]] states that an instance's port runs on a grid and
  * leaves the connection to [[materialize]], which is what lets a device file state its own clocking.
  */
object Clocks:

    /** The wizard's IP instance name: the tcl and the constraints' clock names spell it. */
    val ipName = "clk_wiz_0"

    /** The grids the design asked for, in first-ask order (a bind asks as well) — the plan, `clk_out1` first. Keyed by
      * the grid itself, `(frequency, phase)`, which is what a caller knows about a clock.
      */
    private val plan = mutable.LinkedHashSet.empty[ClockOut]

    /** The wires [[clock]] handed its callers: the grids whose connection the caller writes itself. */
    private val wires = mutable.LinkedHashMap.empty[ClockOut, Wire[Clock]]

    /** What [[bind]] left to the mechanism: `(an instance's port, the grid it runs on)`, connected when the wizard is
      * built. The port belongs to the block that names the instance — the block [[materialize]] runs in.
      */
    private val bound = mutable.ArrayBuffer.empty[(Ref[Clock], ClockOut)]

    private var materialized = false

    DumpRegistry.onReset: () =>
        plan.clear()
        wires.clear()
        bound.clear()
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

    /** Ask for a grid *for an instance's port*: the connection is not the caller's to write, so [[materialize]] writes
      * it — which is what lets a device file state its own clocking where it names its instances. A binding is a
      * statement until it is performed.
      */
    def bind(port: Ref[Clock], grid: ClockOut): Unit =
        plan.add(grid)
        bound += ((port, grid))

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
      * out, and the instance ports [[bind]] stated. Called once ([[nzea_fpga.Top]] does, after everything that wants a
      * grid has asked for one); returns the wizard's `locked`, which the domains behind it wait on.
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
