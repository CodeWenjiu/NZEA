package nzea_fpga.ip

import java.lang.foreign.Arena

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.DumpRegistry
import nzea_rtl.FileList
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.mutable

/** The wizard's primitive: the IP is the same one either way — Vivado's `clk_wiz` — and which primitive it configures
  * is a fact of the plan ([[ClockPrimitive.forPlan]]), not a choice of the caller's.
  *
  * Where the PLL can express a plan exactly, it is the one to use (smaller, lower-jitter). `outputs` is how many grids one
  * of them serves; past it a design needs a second generator, and a plan split across two blocks puts an unknown phase
  * between their outputs ([[nzea_fpga.Clocks]] refuses the split rather than taking it).
  */
enum ClockPrimitive(val name: String, val outputs: Int):
    case Mmcm extends ClockPrimitive("MMCM", 7)
    case Pll extends ClockPrimitive("PLL", 4)

object ClockPrimitive:

    private case class KHz(min: Long, max: Long)

    /** The PLL's limits (UG472, the 7-series PLLE2), on the *narrow* side of what the part allows: a plan this search
      * accepts must be one the tool realizes exactly, and a primitive that cannot is silently approximated by the IP
      * (measured: a PLL forced onto a plan it cannot hit comes back ~100 ppm off, with no warning).
      */
    private val pllPfd = KHz(10_000L, 450_000L)
    private val pllVco = KHz(800_000L, 1_600_000L)
    private val pllCounter = 2 to 64
    private val pllInputDivide = 1 to 106
    private val pllOutputDivide = 1 to 128

    /** The MMCM's limits (UG472, the 7-series MMCME2) on the part this board carries (`xc7a200t`, -2 speed grade, which
      * is the board's fact, not the design's).
      */
    private val mmcmPfd = KHz(10_000L, 450_000L)
    private val mmcmVco = KHz(600_000L, 1_440_000L)
    private val mmcmCounter = 2 to 64
    private val mmcmInputDivide = 1 to 106
    private val mmcmOutputDivide = 1 to 128

    /** The MMCM's fine phase grid: one step is a 56th of a VCO period, and a phase is realizable exactly when it lands
      * on whole steps. Public because the tcl states the steps it placed.
      */
    val PhaseSteps = 56

    def forPlan(plan: ClockPlan): ClockPrimitive =
        if plan.outputs.sizeIs <= Pll.outputs && plan.outputs.forall(_.phase == 0.0) && pllSolution(plan).isDefined then
            Pll
        else Mmcm

    /** An exact PLL solution for the plan, if there is one: one input divider and multiplier, every output an integer
      * divide of that one VCO node. The plan's frequencies are whole kHz ([[ClockPlan]]), so "exactly" is checked
      * exactly.
      */
    private def pllSolution(plan: ClockPlan): Option[(Int, Int)] =
        val fin = math.round(plan.inputMHz * 1000)
        val outs = plan.outputs.map(out => math.round(out.mhz * 1000))
        (for
            d <- pllInputDivide
            if fin >= pllPfd.min * d && fin <= pllPfd.max * d
            m <- pllCounter
            vco = fin * m
            if vco >= pllVco.min * d && vco <= pllVco.max * d
            if outs.forall(out => vco % (d * out) == 0 && pllOutputDivide.contains((vco / (d * out)).toInt))
        yield (d, m)).headOption

    case class MmcmOutput(divide: Int, phaseSteps: Int)
    case class MmcmSolution(inputDivide: Int, mult: Int, outputs: Seq[MmcmOutput])

    /** The exact MMCM solution for a plan, if there is one: a phase is realized by delaying the output's edge by whole
      * fine steps ([[PhaseSteps]]), so a plan is accepted only when every requested phase lands on that grid exactly
      * (`phase/360 × divide × 56` is a whole step count).
      */
    def mmcmSolution(plan: ClockPlan): Option[MmcmSolution] =
        val fin = math.round(plan.inputMHz * 1000)
        val steps = plan.outputs.map: out =>
            val mhz = math.round(out.mhz * 1000)
            val phaseMilliDeg = math.round(out.phase * 1000)
            (mhz, phaseMilliDeg)
        (for
            d <- mmcmInputDivide
            if fin >= mmcmPfd.min * d && fin <= mmcmPfd.max * d
            m <- mmcmCounter
            vco = fin * m
            if vco >= mmcmVco.min * d && vco <= mmcmVco.max * d
            if steps.forall { (mhz, _) => vco % (d * mhz) == 0 }
            outputs = steps.map: (mhz, phaseMilliDeg) =>
                val divide = (vco / (d * mhz)).toInt
                // `phase/360 × divide × 56` steps of a VCO period: the whole-step requirement *is* the exactness check.
                val numerator = phaseMilliDeg * divide * PhaseSteps
                val phaseSteps = (numerator / 360000).toInt
                (divide, phaseSteps, numerator % 360000 == 0)
            if outputs.forall { (divide, phaseSteps, exact) =>
                exact && mmcmOutputDivide.contains(divide) && phaseSteps >= 0 && phaseSteps < PhaseSteps * divide
            }
        yield MmcmSolution(d, m, outputs.map((divide, phaseSteps, _) => MmcmOutput(divide, phaseSteps)))).headOption

/** One output the plan asks the wizard for: the frequency to run at, and the phase to hold, in degrees of that
  * frequency's own period — a source-synchronous interface is timed by where its edges sit, so the phase is stated here,
  * not derived.
  */
case class ClockOut(mhz: Double, phase: Double):
    require(mhz > 0, s"every output must be positive, got $mhz")
    require(phase >= 0.0 && phase < 360.0, s"phase is in degrees of the output's own period, in [0, 360), got $phase")

/** A clock plan, in MHz: what the wizard makes of its input. The outputs are in order — `outputs(0)` is `clk_out1` —
  * and they are also the port list the RTL declares, so one plan is one interface. A design states it by asking for its
  * grids ([[nzea_fpga.Clocks]]), one request per output.
  *
  * Frequencies are on the kHz grid and phases on the millidegree grid: the IP's own fields are written with three
  * decimals, so a plan off the grid would be silently rounded by the IP.
  */
case class ClockPlan(inputMHz: Double, outputs: Seq[ClockOut]):
    require(inputMHz > 0, s"inputMHz must be positive, got $inputMHz")
    require(outputs.nonEmpty, "a clock plan needs at least one output")

    require(
        // `scale` is the number of decimals the value itself carries: the grid, not a rounding of it.
        (inputMHz +: outputs.map(_.mhz)).forall(freq => BigDecimal(freq).scale <= 3),
        s"frequencies are on the kHz grid (the IP's own three decimals): $inputMHz -> $outputs"
    )

    /** The primitive these frequencies need ([[ClockPrimitive.forPlan]]). */
    def primitive: ClockPrimitive = ClockPrimitive.forPlan(this)

given upickle.default.ReadWriter[ClockOut] = upickle.default.macroRW
given upickle.default.ReadWriter[ClockPlan] = upickle.default.macroRW

object ClockOut:

    /** The output's spelling inside a plan: `outMHz`, or `outMHz@deg` when the phase is not zero. */
    def parse(token: String): Either[String, ClockOut] =
        token.split('@') match
            case Array(mhz) => mhz.toDoubleOption.map(ClockOut(_, 0.0)).toRight(s"output '$mhz' is not a frequency")
            case Array(mhz, phase) =>
                for
                    freq <- mhz.toDoubleOption.toRight(s"output '$mhz' is not a frequency")
                    deg <- phase.toDoubleOption.toRight(s"phase '$phase' is not an angle")
                    out <- scala.util.Try(ClockOut(freq, deg)).toEither.left.map(_.getMessage)
                yield out
            case _ => Left(s"expected outMHz or outMHz@deg, got '$token'")

object ClockPlan:

    /** The flag's spelling: `inMHz:outMHz[@deg][,outMHz[@deg]...]`, the plan's fields in order. */
    def parse(token: String): Either[String, ClockPlan] =
        token.split(':') match
            case Array(input, outputs) =>
                for
                    inputMHz <- input.toDoubleOption.toRight(s"input '$input' is not a frequency")
                    outs <- outputs
                        .split(',')
                        .foldLeft[Either[String, Seq[ClockOut]]](Right(Seq.empty)): (acc, out) =>
                            for
                                done <- acc
                                parsed <- ClockOut.parse(out)
                            yield done :+ parsed
                    plan <- scala.util.Try(ClockPlan(inputMHz, outs)).toEither.left.map(_.getMessage)
                yield plan
            case _ => Left(s"expected inMHz:outMHz[@deg][,...], got '$token'")

    given TokensReader.Simple[ClockPlan] with
        def shortName = "inMHz:outMHz[@deg][,...]"

        def read(strs: Seq[String]): Either[String, ClockPlan] =
            strs.lastOption.map(parse).getOrElse(Left("clock plan requires a value"))

case class ClockingWizardParameter(ipName: String, plan: ClockPlan) extends Parameter:
    require(ipName.nonEmpty, "ipName must not be empty")

given upickle.default.ReadWriter[ClockingWizardParameter] = upickle.default.macroRW

class ClockingWizardLayers(parameter: ClockingWizardParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The IP's ports: one output per plan output — the port list follows the plan, as the IP's own does (it is created
  * with `CLKOUTn_USED` per output).
  */
class ClockingWizardIO(parameter: ClockingWizardParameter) extends HWRecord(parameter):
    Flipped("clk_in1", Clock())
    Flipped("reset", Bool())
    Aligned("locked", Bool())
    for (out, i) <- parameter.plan.outputs.zipWithIndex do Aligned(s"clk_out${i + 1}", Clock())

class ClockingWizardProbe(parameter: ClockingWizardParameter)
    extends DVRecord[ClockingWizardParameter, ClockingWizardLayers](parameter)

case class ClockingWizardVerilogParameter() extends VerilogParameter

/** The wrapper the RTL names: the module and the Verilog module are both `parameter.ipName`, so the placeholder the
  * parent's circuit carries for the instance and the extmodule this wrapper emits merge.
  */
@generator
object ClockingWizardModule
    extends VerilogWrapper[
        ClockingWizardParameter,
        ClockingWizardLayers,
        ClockingWizardIO,
        ClockingWizardProbe,
        ClockingWizardVerilogParameter
    ]:

    override def moduleName(parameter: ClockingWizardParameter): String = parameter.ipName

    override def verilogModuleName(parameter: ClockingWizardParameter): String = parameter.ipName

    /** No Verilog parameters: the configuration is the IP's, stated by the tcl beside it. */
    def verilogParameter(parameter: ClockingWizardParameter): ClockingWizardVerilogParameter =
        ClockingWizardVerilogParameter()

/** The clocking wizard IP: the tcl the project creates it from is generated here, from the same plan the port list came
  * from, so the IP and the RTL cannot disagree about the interface. Instantiate through [[ClockingWizard.instantiate]]
  * (not the wrapper's own) so the dump records the IP it has to write the tcl for; a dump that instantiated none writes
  * nothing.
  */
object ClockingWizard:

    private val instantiated = mutable.LinkedHashMap.empty[String, ClockingWizardParameter]

    DumpRegistry.onReset(() => instantiated.clear())

    /** The IP's tcl: create it, configure it from the plan, and generate its targets.
      *
      * A pure function, so a test can pin the text. The CONFIG spellings are Xilinx's and version-sensitive; they live
      * here rather than in the project generator because edalize deliberately does not model vendor-IP configuration.
      *
      * The configuration is *requested*: the wizard's achieved fields are its own generated properties, which these
      * Vivado versions reject writing or reading (measured: `Cannot find parameter 'CLKOUT1_DIVIDE'` on the freshly
      * created IP), so the plan states frequencies and phases and the wizard's solver realizes them. What keeps that
      * honest is the plan's exactness check: a phase is only accepted when a VCO exists whose fine grid lands on it
      * ([[ClockPrimitive.mmcmSolution]]).
      */
    def tcl(parameter: ClockingWizardParameter): String =
        val plan = parameter.plan
        // The frequencies as the IP's own properties spell them (three decimals, as the wizard writes them back):
        // pre-formatted, because the interpolator treats a format suffix as text.
        val inputMHz = f"${plan.inputMHz}%.3f"
        val outputs = plan.outputs.zipWithIndex
            .map: (out, i) =>
                val n = i + 1
                val freq = f"${out.mhz}%.3f"
                val phase = f"${out.phase}%.3f"
                s"  CONFIG.CLKOUT${n}_USED {true} \\\n" +
                    s"  CONFIG.CLKOUT${n}_REQUESTED_OUT_FREQ {$freq} \\\n" +
                    s"  CONFIG.CLKOUT${n}_REQUESTED_PHASE {$phase} \\\n"
            .mkString
        require(
            plan.outputs.forall(_.phase == 0.0) || ClockPrimitive.mmcmSolution(plan).isDefined,
            s"the plan's phases are not on the MMCM's fine phase grid (1/${ClockPrimitive.PhaseSteps} of a VCO period): $plan"
        )
        s"""# Auto-generated clocking wizard snippet (from ClockPlan(${plan.inputMHz}, ${plan.outputs
              .map(out => s"${out.mhz}@${out.phase}")
              .mkString(", ")}, ${plan.primitive.name})).
           |create_ip -name clk_wiz -vendor xilinx.com -library ip -module_name ${parameter.ipName}
           |set_property -dict [list \\
           |  CONFIG.PRIMITIVE {${plan.primitive.name}} \\
           |  CONFIG.PRIM_IN_FREQ {$inputMHz} \\
           |$outputs  CONFIG.USE_LOCKED {true} \\
           |  CONFIG.USE_RESET {true} \\
           |] [get_ips ${parameter.ipName}]
           |generate_target all [get_ips ${parameter.ipName}]
           |""".stripMargin

    /** Write one tcl per instantiated IP into `dir` and list them for the project generator; also list each IP's
      * structural sim netlist — `$ipName/${ipName}_sim_netlist.v`, the path it takes under the generated-IP root (one
      * directory per module name) — for the xsim flow.
      */
    def emit(dir: os.Path): Unit =
        if instantiated.nonEmpty then
            os.makeDir.all(dir)
            val names = instantiated.map { (ipName, parameter) =>
                val name = s"ip_$ipName.tcl"
                os.write.over(dir / name, tcl(parameter))
                name
            }.toSeq
            FileList.appendIp(dir, names)
            FileList.appendSim(dir, instantiated.keys.toSeq.map(ipName => s"$ipName/${ipName}_sim_netlist.v"))

    def instantiate(parameter: ClockingWizardParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[ClockingWizardIO, ClockingWizardProbe] =
        DumpRegistry.onEmit(s"clk_wiz:${parameter.ipName}")(dir => ClockingWizard.emit(dir))
        instantiated.put(parameter.ipName, parameter)
        ClockingWizardModule.instantiate(parameter)
