package nzea_cli

import mainargs.{arg, main, ParserForClass}
import nzea_core.config.CoreConfig
import nzea_fpga.{FpgaElaborate, TopParameter}
import nzea_rtl.{DumpRegistry, Elaborate, RtlFlow}
import nzea_rtl.stat.{StatRegistry, StatSchema}
import nzea_tile.*
import nzea_top.{NzeaCore, NzeaCoreElaborate, NzeaCoreParameter, TopConfig, TopConfigArgs}

/** The flag surface of a dump: one field per flag, the doc string its help text.
  *
  * A default that describes the command (which level, where the RTL goes) lives here; one that picks out the design
  * derives from the level's `default` parameter, so the baseline is stated once. The tree's flags are mirrors
  * (`TopConfigArgs` → `CoreConfigArgs`, beside the baseline in `nzea_top`), and mainargs flattens the groups — the flags
  * stay `--isa` / `--defaultPc` — and accepts both spellings of a field name (`--defaultPc` and `--default-pc`). The
  * devices flag is the tile level's own knob and is ignored by the core target.
  */
@main(name = "nzea dump", doc = "Elaborate one design to Verilog. The core, tile and fpga targets are ported.")
case class CliArgs(
    @arg(doc = "which level to elaborate") target: String = "core",
    @arg(doc = "the platform the RTL is shaped for; an fpga build's platform is its --board") platform: String =
        "yosys",
    config: TopConfigArgs,
    @arg(doc = "verilator-shaped RTL or synthesis-shaped RTL; a board is always synthesis-shaped")
    flow: RtlFlow = CliArgs.coreBaseline.flow,
    @arg(doc = "the tile's devices (JSON, repeatable); defaults to the platform's") devices: Seq[DeviceSpec],
    @arg(doc = "load/store queue depth (a power of two), 0 builds none") lsqDepth: Int = CliArgs.coreBaseline.lsqDepth,
    @arg(doc = "instruction cache: a geometry, or 'none'") icache: CacheChoice =
        CacheChoice.of(CliArgs.tileBaseline.icache),
    @arg(doc = "data cache: a geometry, or 'none'") dcache: CacheChoice = CacheChoice.of(CliArgs.tileBaseline.dcache),
    @arg(doc = "the FPGA board (--target fpga)") board: String = "lxb_artix7",
    @arg(doc = "output dir; defaults to build/<target>/<platform>/<isa>") outDir: String = ""
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

    /** The tile level's design: the tile's own baseline, with the ISA, flow, device list and external windows a flag
      * named overridden.
      *
      * The tile's external windows are not a dump flag: a window the level above answers exists only where there is a
      * level above (its parameter carries them — [[NzeaTileParameter.externals]]), so a CLI dump is the baseline
      * platform, with none.
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
            icache = CacheChoice.toOption(icache),
            dcache = CacheChoice.toOption(dcache),
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

    /** firtool flags per flow: Verilator takes what firtool emits by default, while Yosys refuses `always_comb` and
      * local variables inside it, so a synthesis dump is lowered without either.
      *
      * What every flow's lowering shares: the split output, and the randomization/debug stripping the dumps are read
      * with. What a value keeps of itself — its declaration, and the Verilog attributes it carries — is not decided
      * here: `dontTouch` marks the values that must survive, per value, and the rest of the design is lowered the way
      * the emitter wants to (see `ConstructorApi.NodeWithAttributes`).
      */
    private val sharedLowering = Seq(
        "--split-verilog",
        "-disable-all-randomization",
        "-strip-debug-info"
    )

    private val yosysLowering =
        sharedLowering :+ "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays,noAlwaysComb"

    private def lowering(flow: RtlFlow): Seq[String] = flow match
        case RtlFlow.Verilator => sharedLowering
        case RtlFlow.Yosys     => yosysLowering

    def main(args: Array[String]): Unit =
        val cli = ParserForClass[CliArgs].constructOrExit(args.toSeq)
        require(cli.platform == "yosys" || cli.platform == "fpga", s"unknown platform '${cli.platform}'")
        // The build tree is `<target>/<platform>/<isa>` under `build/`: which level, what it is built for, and the ISA.
        // The flow is what a dump *is*, not where it goes — a path names one directory per design, so a level's
        // directory holds the dump its last command made (the simulators name their own with `--outDir`). An fpga
        // build's platform is its board: it is what the RTL is shaped for, and it is how boards stay apart.
        val platform = if cli.target == "fpga" then cli.board else cli.platform
        val outDir =
            if cli.outDir.nonEmpty then os.Path(cli.outDir, os.pwd)
            else os.pwd / "build" / cli.target / platform / cli.isaName

        // Generating a level is that level's top's concern (`NzeaCoreElaborate` / `NzeaTileElaborate` / `FpgaElaborate`);
        // what stays here is the CLI's: lowering the linked circuit and writing the bodies that travel with the RTL.
        cli.target match
            case "core" => dumpCore(cli.coreParameter, outDir)
            case "tile" => dumpTile(cli.tileParameter, outDir)
            case "fpga" => dumpFpga(cli, outDir)
            case other  => require(false, s"unknown --target '$other' (ported: core, tile, fpga)")

    private def dumpCore(parameter: NzeaCoreParameter, outDir: os.Path): Unit =
        NzeaCoreElaborate.withLinked(parameter): circuit =>
            Elaborate.verilogInto(circuit, outDir, lowering(parameter.flow))
            emitCounters(outDir, parameter.flow, NzeaCore.moduleName(parameter))
            DumpRegistry.emitAll(outDir)
        println(s"elaborated core:${parameter.config.core.isa.name} into $outDir")

    private def dumpTile(parameter: NzeaTileParameter, outDir: os.Path): Unit =
        NzeaTileElaborate.withLinked(parameter): circuit =>
            Elaborate.verilogInto(circuit, outDir, lowering(parameter.flow))
            emitCounters(outDir, parameter.flow, NzeaTile.moduleName(parameter))
            DumpRegistry.emitAll(outDir)
        println(s"elaborated tile:${parameter.config.isa.name} into $outDir")

    /** The FPGA level: a board top, always shaped for synthesis — a board build is not a flow's choice, so `--flow`
      * does not reach it. Every part that is used registers its own artifacts as it is instantiated ([[DumpRegistry]]),
      * so the CLI only asks for what was registered.
      */
    private def dumpFpga(cli: CliArgs, outDir: os.Path): Unit =
        require(cli.board == "lxb_artix7", s"unknown --board '${cli.board}' (ported: lxb_artix7)")
        val parameter = TopParameter()
        FpgaElaborate.withLinked(parameter): circuit =>
            Elaborate.verilogInto(circuit, outDir, yosysLowering)
            DumpRegistry.emitAll(outDir)
        println(s"elaborated fpga:${cli.board} into $outDir")

    /** The counter schema, written beside the RTL it belongs to: a simulator reads the counters through VPI and
      * interprets them through the schema. It is the one artifact that stays in the CLI's hands, because it is the one
      * that depends on *how* the dump is taken (a flow that carries no counters has no schema) rather than on what the
      * design used — everything else registers itself with [[DumpRegistry]].
      */
    private def emitCounters(outDir: os.Path, flow: RtlFlow, design: String): Unit =
        if flow.counters then
            StatRegistry.emitBoxes(outDir)
            StatSchema.emit(outDir.toString, design, StatRegistry.schema)
