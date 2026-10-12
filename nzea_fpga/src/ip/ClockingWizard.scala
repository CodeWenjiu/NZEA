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
  * An MMCM is a PLL plus fractional divide, finer phase steps and more outputs. Where the PLL can express a plan
  * exactly, it is the one to use: it is the smaller, lower-jitter block, and a superset's whole point is to hide behind
  * the subset, not to be asked for.
  */
enum ClockPrimitive(val name: String):
    case Mmcm extends ClockPrimitive("MMCM")
    case Pll extends ClockPrimitive("PLL")

object ClockPrimitive:

    /** A range of the PLL's own limits, in kHz. */
    private case class KHz(min: Long, max: Long)

    /** The PLL's limits (UG472, the 7-series PLLE2). They are the *narrow* side of what the part allows, because a plan
      * this search accepts must be one the tool realizes exactly — a primitive that cannot is silently approximated by
      * the IP (measured: a PLL forced onto a plan it cannot hit comes back ~100 ppm off, with no warning).
      */
    private val pllPfd = KHz(10_000L, 450_000L)
    private val pllVco = KHz(800_000L, 1_600_000L)
    private val pllCounter = 2 to 64
    private val pllInputDivide = 1 to 106
    private val pllOutputDivide = 1 to 128
    private val pllOutputs = 4

    /** The primitive a plan needs: the PLL when a PLL configuration expresses it exactly, the MMCM (the superset)
      * otherwise. The plan is what the caller states; this is what those frequencies mean.
      */
    def forPlan(plan: ClockPlan): ClockPrimitive =
        if plan.outputs.sizeIs <= pllOutputs && pllSolution(plan).isDefined then Pll else Mmcm

    /** An exact PLL solution for the plan, if there is one: one input divider and multiplier put the VCO inside the
      * PLL's range and its phase detector inside its own, and every output is an integer divide of that one VCO node.
      * The counters are small and few, so the search is the direct one; the plan's frequencies are whole kHz
      * ([[ClockPlan]]), so "exactly" is checked exactly.
      */
    private def pllSolution(plan: ClockPlan): Option[(Int, Int)] =
        val fin = math.round(plan.inputMHz * 1000)
        val outs = plan.outputs.map(freq => math.round(freq * 1000))
        (for
            d <- pllInputDivide
            if fin >= pllPfd.min * d && fin <= pllPfd.max * d
            m <- pllCounter
            vco = fin * m
            if vco >= pllVco.min * d && vco <= pllVco.max * d
            if outs.forall(out => vco % (d * out) == 0 && pllOutputDivide.contains((vco / (d * out)).toInt))
        yield (d, m)).headOption

/** The board's clock plan, in MHz: what the wizard makes of its input. The outputs are in order — `outputs(0)` is
  * `clk_out1` — and they are also the port list the RTL declares, so one plan is one interface.
  *
  * Frequencies are on the kHz grid: the IP's own fields are written with three decimals ([[ClockingWizard.tcl]]), so a
  * plan off the grid would be silently rounded by the IP — and the primitive the plan needs is decided by comparing
  * frequencies exactly ([[primitive]]).
  */
case class ClockPlan(inputMHz: Double, outputs: Seq[Double]):
    require(inputMHz > 0, s"inputMHz must be positive, got $inputMHz")
    require(outputs.nonEmpty, "a clock plan needs at least one output")
    require(outputs.forall(_ > 0), s"every output must be positive, got $outputs")

    require(
        // `scale` is the number of decimals the value itself carries: the grid, not a rounding of it.
        (inputMHz +: outputs).forall(freq => BigDecimal(freq).scale <= 3),
        s"frequencies are on the kHz grid (the IP's own three decimals): $inputMHz -> $outputs"
    )

    /** The primitive these frequencies need ([[ClockPrimitive.forPlan]]). */
    def primitive: ClockPrimitive = ClockPrimitive.forPlan(this)

given upickle.default.ReadWriter[ClockPlan] = upickle.default.macroRW

object ClockPlan:

    /** The flag's spelling: `inMHz:outMHz[,outMHz...]`, the plan's fields in order. */
    def parse(token: String): Either[String, ClockPlan] =
        token.split(':') match
            case Array(input, outputs) =>
                for
                    inputMHz <- input.toDoubleOption.toRight(s"input '$input' is not a frequency")
                    freqs <- outputs
                        .split(',')
                        .foldLeft[Either[String, Seq[Double]]](Right(Seq.empty)): (acc, out) =>
                            for
                                done <- acc
                                freq <- out.toDoubleOption.toRight(s"output '$out' is not a frequency")
                            yield done :+ freq
                    plan <- scala.util.Try(ClockPlan(inputMHz, freqs)).toEither.left.map(_.getMessage)
                yield plan
            case _ => Left(s"expected inMHz:outMHz[,outMHz...], got '$token'")

    given TokensReader.Simple[ClockPlan] with
        def shortName = "inMHz:outMHz[,outMHz...]"

        def read(strs: Seq[String]): Either[String, ClockPlan] =
            strs.lastOption.map(parse).getOrElse(Left("clock plan requires a value"))

case class ClockingWizardParameter(ipName: String, plan: ClockPlan) extends Parameter:
    require(ipName.nonEmpty, "ipName must not be empty")

given upickle.default.ReadWriter[ClockingWizardParameter] = upickle.default.macroRW

class ClockingWizardLayers(parameter: ClockingWizardParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The IP's ports: the input, the reset, `locked`, and one output per plan output — the port list follows the plan, as
  * the IP's own does (it is created with `CLKOUTn_USED` per output).
  */
class ClockingWizardIO(parameter: ClockingWizardParameter) extends HWRecord(parameter):
    Flipped("clk_in1", Clock())
    Flipped("reset", Bool())
    Aligned("locked", Bool())
    for (freq, i) <- parameter.plan.outputs.zipWithIndex do Aligned(s"clk_out${i + 1}", Clock())

class ClockingWizardProbe(parameter: ClockingWizardParameter)
    extends DVRecord[ClockingWizardParameter, ClockingWizardLayers](parameter)

case class ClockingWizardVerilogParameter() extends VerilogParameter

/** The wrapper the RTL names: [[ClockingWizard]] instantiates the IP as `parameter.ipName`, so the module and the
  * Verilog module are that name — the placeholder the parent's circuit carries for the instance and the extmodule this
  * wrapper emits only merge when the two agree.
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
  * from, so the IP and the RTL cannot disagree about the interface.
  *
  * Instantiate through [[ClockingWizard.instantiate]], not the wrapper's own, so the dump records the IP it has to
  * write the tcl for; a dump that instantiated none writes nothing.
  */
object ClockingWizard:

    /** The IPs this elaboration instantiated, keyed by name, first use first. */
    private val instantiated = mutable.LinkedHashMap.empty[String, ClockingWizardParameter]

    DumpRegistry.onReset(() => instantiated.clear())

    /** The IP's tcl: create it, configure it from the plan, and generate its targets.
      *
      * A pure function, so a test can pin the text. The CONFIG spellings are Xilinx's and version-sensitive; they live
      * here rather than in the project generator because edalize deliberately does not model vendor-IP configuration.
      */
    def tcl(parameter: ClockingWizardParameter): String =
        val plan = parameter.plan
        // The frequencies as the IP's own properties spell them (three decimals, as the wizard writes them back):
        // pre-formatted, because the interpolator treats a format suffix as text.
        val inputMHz = f"${plan.inputMHz}%.3f"
        val outputs = plan.outputs.zipWithIndex
            .map: (freq, i) =>
                val out = f"${freq}%.3f"
                s"  CONFIG.CLKOUT${i + 1}_USED {true} \\\n  CONFIG.CLKOUT${i + 1}_REQUESTED_OUT_FREQ {$out} \\\n"
            .mkString
        s"""# Auto-generated clocking wizard snippet (from ClockPlan(${plan.inputMHz}, ${plan.outputs}, ${plan.primitive.name})).
           |create_ip -name clk_wiz -vendor xilinx.com -library ip -module_name ${parameter.ipName}
           |set_property -dict [list \\
           |  CONFIG.PRIMITIVE {${plan.primitive.name}} \\
           |  CONFIG.PRIM_IN_FREQ {$inputMHz} \\
           |$outputs  CONFIG.USE_LOCKED {true} \\
           |  CONFIG.USE_RESET {true} \\
           |] [get_ips ${parameter.ipName}]
           |generate_target all [get_ips ${parameter.ipName}]
           |""".stripMargin

    /** Write one tcl per instantiated IP into `dir` and add them to its IP list. */
    def emit(dir: os.Path): Unit =
        if instantiated.nonEmpty then
            os.makeDir.all(dir)
            val names = instantiated.map { (ipName, parameter) =>
                val name = s"ip_$ipName.tcl"
                os.write.over(dir / name, tcl(parameter))
                name
            }.toSeq
            FileList.appendIp(dir, names)

    /** Instantiate the IP, recording it so the dump writes its tcl. */
    def instantiate(parameter: ClockingWizardParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[ClockingWizardIO, ClockingWizardProbe] =
        instantiated.put(parameter.ipName, parameter)
        ClockingWizardModule.instantiate(parameter)
