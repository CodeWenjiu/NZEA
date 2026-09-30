package nzea_cli

import nzea_config.NzeaCoreParameter
import nzea_core.{BpuLevel, BypassLevel}
import nzea_core.isa.Isa
import nzea_rtl.{Elaborate, RtlFlow, StatRegistry, StatSchema, StatScope}
import nzea_top.NzeaCore

/** Elaborate one design to Verilog.
  *
  * The flags the simulators pass — `just dump --target core --isa <isa> --outDir <dir>` — are the contract; the others
  * have defaults for local runs. Only the core target exists so far.
  */
object Main:

  /** The yosys platform's device windows, RAM excluded (it is the one memory range): the addresses the core marks as
    * MMIO in the commit trace. The tile owns this map once it lands.
    */
  private val mmioWindows: Seq[String] = Seq("0x10000000:8", "0x00100000:4", "0x02000000:0xc000")

  private val usage =
    """usage: just dump [--target core] [--platform yosys] [--isa riscv32i] [--defaultPc 0x80000000]
      |                 [--flow <verilator|yosys>] [--stats <root|a.b>] [--outDir <dir>]""".stripMargin

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
    val flags = parse(args.toList)
    val target = flags.getOrElse("target", "core")
    val platform = flags.getOrElse("platform", "yosys")
    val isa = Isa.parseOrThrow(flags.getOrElse("isa", "riscv32i"))
    val defaultPc: Long = flags.get("defaultPc").fold(0x80000000L)(java.lang.Long.decode(_))
    val flow = RtlFlow
      .fromString(flags.getOrElse("flow", RtlFlow.Verilator.segment))
      .fold(error => throw new IllegalArgumentException(error), identity)
    // Fixed for now: the commit level is the baseline's only forwarding choice, and it is not a user knob yet. Edit
    // this line to build the design without it.
    val bypass = BypassLevel.Execute
    // Fixed for now too: the fetch's predictor — `Bimodal` builds its tables, `Off` is the sequential fetch. Edit this
    // line to switch levels.
    val bpu = BpuLevel.Bimodal(64, 4)
    val outDir = flags
      .get("outDir")
      .filter(_.nonEmpty)
      .fold(os.pwd / "build" / target / platform / isa.name / flow.segment)(os.Path(_, os.pwd))

    require(target == "core", s"only --target core is ported, got '$target'")
    require(platform == "yosys" || platform == "fpga", s"unknown platform '$platform'")

    val parameter = NzeaCoreParameter(isa, defaultPc, flow, mmioWindows, bypass, bpu, StatScope.root)
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
              isa.name,
              "--defaultPc",
              defaultPc.toString,
              "--flow",
              flow.segment,
              "--bypass",
              bypass.name,
              "--bpu",
              bpu.name,
              // The scope the design's own counters hang under; a dump nests them by the blocks that declare them.
              "--stats",
              "root"
            ) ++ mmioWindows.flatMap(window => Seq("--mmioRanges", window))
          ),
        cfg => NzeaCore.main(Array("design", cfg.toString))
      ): circuit =>
        Elaborate.verilogInto(circuit, outDir, lowering(flow))
        // The counters and the schema that describes them travel with the RTL they belong to: a simulator reads the
        // counters through VPI and interprets them through the schema.
        if flow.counters then
          StatRegistry.emitBoxes(outDir)
          StatSchema.emit(outDir.toString, NzeaCore.moduleName(parameter), StatRegistry.schema)
    finally os.remove.all(scratch)

    println(s"elaborated $target:${isa.name} for $platform into $outDir")

  /** `--flag value` pairs; every flag takes a value, so a missing one is an error rather than a silent default. */
  private def parse(args: List[String]): Map[String, String] = args match
    case Nil                                            => Map.empty
    case flag :: value :: rest if flag.startsWith("--") => parse(rest) + (flag.stripPrefix("--") -> value)
    case flag :: _ => throw new IllegalArgumentException(s"'$flag' expects a value\n$usage")
