package nzea_cli

import mainargs.{arg, main, ParserForClass}
import nzea_core.config.CoreConfig
import nzea_rtl.{Elaborate, RtlFlow}
import nzea_rtl.stat.{StatRegistry, StatSchema}
import nzea_rtl.stdlib.SyncRam
import nzea_tile.*
import nzea_top.{NzeaCore, NzeaCoreElaborate, NzeaCoreParameter, TopConfig, TopConfigArgs}

/** The flag surface of a dump: one field per flag, the doc string its help text.
  *
  * A default that describes the command (which level, where the RTL goes) lives here; one that picks out the design
  * derives from the level's `default` parameter, so the baseline is stated once. The tree's flags are mirrors
  * (`TopConfigArgs` → `CoreConfigArgs`, beside the baseline in `nzea_top`): mainargs resolves a nested group's defaults
  * from the group's own fields, and the design types carry none (rule 6), so a mirror keeps the defaults that let one
  * flag be overridden alone. mainargs flattens the groups — the flags stay `--isa` / `--defaultPc` — and accepts both
  * spellings of a field name (`--defaultPc` and `--default-pc`). The map is the tile level's own knob and is ignored by
  * the core target.
  */
@main(name = "nzea dump", doc = "Elaborate one design to Verilog. The core and tile targets are ported.")
case class CliArgs(
    @arg(doc = "which level to elaborate") target: String = "core",
    @arg(doc = "the platform the RTL is shaped for") platform: String = "yosys",
    config: TopConfigArgs,
    @arg(doc = "verilator-shaped RTL or synthesis-shaped RTL") flow: RtlFlow = CliArgs.coreBaseline.flow,
    @arg(doc = "the tile's devices (JSON, repeatable); defaults to the platform's") devices: Seq[DeviceSpec],
    @arg(doc = "load/store queue depth (a power of two), 0 builds none") lsqDepth: Int =
        CliArgs.coreBaseline.lsqDepth,
    @arg(doc = "instruction cache: a geometry, or 'none'") icache: ICacheChoice =
        ICacheChoice.of(CliArgs.tileBaseline.icache),
    @arg(doc = "output dir; defaults to build/<target>/<platform>/<isa>/<flow>") outDir: String = ""
):

    /** The ISA both levels are built for: what the out dir and the message name. */
    def isaName: String = config.core.isa.name

    /** The core level's design: the baseline, with what a flag named overridden. The queue depth is the parameter's own
      * spelling: a power of two, or zero for no queue.
      */
    def coreParameter: NzeaCoreParameter = CliArgs.coreBaseline.copy(
        config = TopConfig(core = CoreConfig(isa = config.core.isa, defaultPc = config.core.defaultPc)),
        flow = flow,
        lsqDepth = lsqDepth
    )

    /** The tile level's design: the tile's own baseline, with the ISA, flow and device list a flag named overridden.
      *
      * A `Seq` flag's reader accepts an empty value, so an absent `--devices` arrives as an empty list rather than the
      * field's default (mainargs never falls back to it); the platform's list is substituted here, and a fabric cannot
      * have zero slaves anyway.
      */
    def tileParameter: NzeaTileParameter =
        val base = CliArgs.tileBaseline
        base.copy(
            config = CoreConfig(isa = config.core.isa, defaultPc = config.core.defaultPc),
            flow = flow,
            lsqDepth = lsqDepth,
            icache = ICacheChoice.toOption(icache),
            devices = if devices.isEmpty then flow.devices else devices
        )

object CliArgs:

    /** What a dump with no flags builds: each level's parameter owns its baseline, and the flag defaults that pick it
      * out derive from the same values, so the two cannot drift apart.
      */
    private val coreBaseline = NzeaCoreParameter.default
    private val tileBaseline = NzeaTileParameter.default

/** Elaborate one design to Verilog.
  *
  * The flags the simulators pass — `just dump --target <level> --isa <isa> --outDir <dir>` — are the contract; the
  * others have defaults for local runs.
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
        require(cli.platform == "yosys" || cli.platform == "fpga", s"unknown platform '${cli.platform}'")
        val outDir =
            if cli.outDir.nonEmpty then os.Path(cli.outDir, os.pwd)
            else os.pwd / "build" / cli.target / cli.platform / cli.isaName / cli.flow.segment

        // Generating a level is that level's top's concern (`NzeaCoreElaborate` / `NzeaTileElaborate`); what stays here
        // is the CLI's: lowering the linked circuit and writing the bodies that travel with the RTL they belong to.
        cli.target match
            case "core" => dumpCore(cli.coreParameter, outDir)
            case "tile" => dumpTile(cli.tileParameter, outDir)
            case other  => require(false, s"unknown --target '$other' (ported: core, tile)")

    private def dumpCore(parameter: NzeaCoreParameter, outDir: os.Path): Unit =
        NzeaCoreElaborate.withLinked(parameter): circuit =>
            Elaborate.verilogInto(circuit, outDir, lowering(parameter.flow))
            emitBodies(outDir, parameter.flow, NzeaCore.moduleName(parameter))
        println(s"elaborated core:${parameter.config.core.isa.name} into $outDir")

    private def dumpTile(parameter: NzeaTileParameter, outDir: os.Path): Unit =
        NzeaTileElaborate.withLinked(parameter): circuit =>
            Elaborate.verilogInto(circuit, outDir, lowering(parameter.flow))
            emitBodies(outDir, parameter.flow, NzeaTile.moduleName(parameter))
        println(s"elaborated tile:${parameter.config.isa.name} into $outDir")

    /** The hand-written bodies and the counter schema the RTL instantiates, written beside the RTL they belong to. */
    private def emitBodies(outDir: os.Path, flow: RtlFlow, design: String): Unit =
        // The sync rams the design instantiates travel with the RTL they belong to, like the counters do.
        SyncRam.emitModules(outDir)
        // A simulator reads the counters through VPI and interprets them through the schema.
        if flow.counters then
            StatRegistry.emitBoxes(outDir)
            StatSchema.emit(outDir.toString, design, StatRegistry.schema)
