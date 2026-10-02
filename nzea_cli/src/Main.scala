package nzea_cli

import mainargs.{arg, main, ParserForClass}
import nzea_config.NzeaCoreParameter
import nzea_core.isa.Isa
import nzea_rtl.{Elaborate, RtlFlow, StatRegistry, StatSchema}
import nzea_rtl.stdlib.SyncRam
import nzea_top.NzeaCore

/** The flag surface of a dump: one field per flag, the doc string its help text.
  *
  * A default that describes the command (which level, where the RTL goes) lives here; one that picks out the design
  * (the ISA, the pc, the flow) derives from `NzeaCoreParameter.default`, so the baseline is stated once. mainargs
  * accepts both spellings of a field name (`--defaultPc` and `--default-pc`), so old command lines keep working.
  */
@main(name = "nzea dump", doc = "Elaborate one design to Verilog. Only the core target is ported.")
case class CliArgs(
    @arg(doc = "which level to elaborate") target: String = "core",
    @arg(doc = "the platform the RTL is shaped for") platform: String = "yosys",
    @arg(doc = "instruction set") isa: Isa = CliArgs.baseline.isa,
    @arg(doc = "reset pc, hex (0x…) or decimal") defaultPc: String = CliArgs.baselinePc,
    @arg(doc = "verilator-shaped RTL or synthesis-shaped RTL") flow: RtlFlow = CliArgs.baseline.flow,
    @arg(doc = "output dir; defaults to build/<target>/<platform>/<isa>/<flow>") outDir: String = ""
)

object CliArgs:

    /** What a dump with no flags builds: the parameter owns the design's baseline, and the flag defaults that pick it
      * out derive from the same value, so the two cannot drift apart.
      */
    private val baseline = NzeaCoreParameter.default

    /** The baseline pc in the flag's spelling: hex, so `java.lang.Long.decode` round-trips it. */
    private val baselinePc = s"0x${java.lang.Long.toHexString(baseline.defaultPc)}"

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
        // The baseline, with the flags layered on: what no flag names — the forwarding level, the predictor, the
        // counter scope — stays as the parameter states it.
        val parameter = NzeaCoreParameter.default.copy(
            isa = cli.isa,
            defaultPc = java.lang.Long.decode(cli.defaultPc),
            flow = cli.flow
        )
        val outDir =
            if cli.outDir.nonEmpty then os.Path(cli.outDir, os.pwd)
            else os.pwd / "build" / target / platform / parameter.isa.name / parameter.flow.segment

        require(target == "core", s"only --target core is ported, got '$target'")
        require(platform == "yosys" || platform == "fpga", s"unknown platform '$platform'")

        val scratch = os.temp.dir(prefix = "nzea-dump")
        try
            Elaborate.withLinked(
                NzeaCore.moduleName(parameter),
                scratch,
                cfg =>
                    NzeaCore.main(
                        Array(
                            "config",
                            cfg.toString,
                            "--isa",
                            parameter.isa.name,
                            "--defaultPc",
                            parameter.defaultPc.toString,
                            "--flow",
                            parameter.flow.segment,
                            "--bypass",
                            parameter.bypass.name,
                            "--bpu",
                            parameter.bpu.name,
                            // The scope the design's own counters hang under; a dump nests them by the blocks that declare them.
                            "--stats",
                            "root"
                        ) ++ parameter.mmioRanges.flatMap(window => Seq("--mmioRanges", window))
                    ),
                cfg => NzeaCore.main(Array("design", cfg.toString))
            ): circuit =>
                Elaborate.verilogInto(circuit, outDir, lowering(parameter.flow))
                // The hand-written bodies the design instantiates (its sync rams) travel with the RTL they belong to, like
                // the counters do.
                SyncRam.emitModules(outDir)
                // The counters and the schema that describes them travel with the RTL they belong to: a simulator reads the
                // counters through VPI and interprets them through the schema.
                if parameter.flow.counters then
                    StatRegistry.emitBoxes(outDir)
                    StatSchema.emit(outDir.toString, NzeaCore.moduleName(parameter), StatRegistry.schema)
        finally os.remove.all(scratch)

        println(s"elaborated $target:${parameter.isa.name} for $platform into $outDir")
