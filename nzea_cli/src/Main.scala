package nzea_cli

import mainargs.{arg, main, ParserForClass}
import nzea_core.config.CoreConfig
import nzea_rtl.{Elaborate, RtlFlow}
import nzea_rtl.stat.{StatRegistry, StatSchema}
import nzea_rtl.stdlib.SyncRam
import nzea_top.{NzeaCore, NzeaCoreElaborate, NzeaCoreParameter, TopConfig, TopConfigArgs}

/** The flag surface of a dump: one field per flag, the doc string its help text.
  *
  * A default that describes the command (which level, where the RTL goes) lives here; one that picks out the design
  * derives from `NzeaCoreParameter.default`, so the baseline is stated once. The tree's flags are mirrors
  * (`TopConfigArgs` → `CoreConfigArgs`, beside the baseline in `nzea_top`): mainargs resolves a nested group's defaults
  * from the group's own fields, and the design types carry none (rule 6), so a mirror keeps the defaults that let one
  * flag be overridden alone. mainargs flattens the groups — the flags stay `--isa` / `--defaultPc` — and accepts both
  * spellings of a field name (`--defaultPc` and `--default-pc`).
  */
@main(name = "nzea dump", doc = "Elaborate one design to Verilog. Only the core target is ported.")
case class CliArgs(
    @arg(doc = "which level to elaborate") target: String = "core",
    @arg(doc = "the platform the RTL is shaped for") platform: String = "yosys",
    config: TopConfigArgs,
    @arg(doc = "verilator-shaped RTL or synthesis-shaped RTL") flow: RtlFlow = CliArgs.baseline.flow,
    @arg(doc = "output dir; defaults to build/<target>/<platform>/<isa>/<flow>") outDir: String = ""
):

    /** The design this command line asks for: the baseline, with what a flag named overridden. */
    def parameter: NzeaCoreParameter = CliArgs.baseline.copy(
        config = TopConfig(core = CoreConfig(isa = config.core.isa, defaultPc = config.core.defaultPc)),
        flow = flow
    )

object CliArgs:

    /** What a dump with no flags builds: the parameter owns the design's baseline, and the flag defaults that pick it
      * out derive from the same value, so the two cannot drift apart.
      */
    private val baseline = NzeaCoreParameter.default

/** Elaborate one design to Verilog.
  *
  * The flags the simulators pass — `just dump --target core --isa <isa> --outDir <dir>` — are the contract; the others
  * have defaults for local runs. Only the core target exists so far.
  */
object Main:

    /** firtool flags per flow. Verilator takes what firtool emits by default; Yosys refuses `always_comb` and local
      * variables inside it, so a synthesis dump is lowered without either.
      */
    private val verilatorLowering =
        Seq("--split-verilog", "-disable-all-randomization", "-strip-debug-info")

    private val yosysLowering = Seq(
        "--split-verilog",
        "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays,noAlwaysComb",
        "-disable-all-randomization",
        "-strip-debug-info"
    )

    private def lowering(flow: RtlFlow): Seq[String] = flow match
        case RtlFlow.Verilator => verilatorLowering
        case RtlFlow.Yosys     => yosysLowering

    def main(args: Array[String]): Unit =
        val cli = ParserForClass[CliArgs].constructOrExit(args.toSeq)
        val target = cli.target
        val platform = cli.platform
        val parameter = cli.parameter
        val outDir =
            if cli.outDir.nonEmpty then os.Path(cli.outDir, os.pwd)
            else os.pwd / "build" / target / platform / parameter.config.core.isa.name / parameter.flow.segment

        require(target == "core", s"only --target core is ported, got '$target'")
        require(platform == "yosys" || platform == "fpga", s"unknown platform '$platform'")

        // Generating the level is the top's concern (`NzeaCoreElaborate`); what stays here is the CLI's: lowering the
        // linked circuit and writing the bodies that travel with the RTL they belong to.
        NzeaCoreElaborate.withLinked(parameter): circuit =>
            Elaborate.verilogInto(circuit, outDir, lowering(parameter.flow))
            // The hand-written bodies the design instantiates (its sync rams) travel with the RTL they belong to, like
            // the counters do.
            SyncRam.emitModules(outDir)
            // The counters and the schema that describes them travel with the RTL they belong to: a simulator reads the
            // counters through VPI and interprets them through the schema.
            if parameter.flow.counters then
                StatRegistry.emitBoxes(outDir)
                StatSchema.emit(outDir.toString, NzeaCore.moduleName(parameter), StatRegistry.schema)

        println(s"elaborated $target:${parameter.config.core.isa.name} for $platform into $outDir")
