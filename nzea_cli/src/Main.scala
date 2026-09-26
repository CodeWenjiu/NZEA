package nzea_cli

import nzea_core.stats.{BpCounters, BpCountersParameter, CoreStats, ThroughputCounters, ThroughputCountersParameter}
import nzea_rtl.{Elaborate, StatSchema, StatsRegs}
import nzea_top.{NzeaCore, NzeaCoreParameter}

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
      |                 [--sim true] [--outDir <dir>]""".stripMargin

  /** firtool flags per dump shape. A simulation dump is only ever read by Verilator, but Yosys refuses what firtool
    * emits by default — `always_comb` and local variables inside it — so the synthesis top is lowered without either.
    */
  private val dpiLowering =
    Seq("--split-verilog", "-disable-all-randomization", "-strip-debug-info")

  private val hwLowering = Seq(
    "--split-verilog",
    "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays,noAlwaysComb",
    "-disable-all-randomization",
    "-strip-debug-info"
  )

  def main(args: Array[String]): Unit =
    val flags = parse(args.toList)
    val target = flags.getOrElse("target", "core")
    val platform = flags.getOrElse("platform", "yosys")
    val isa = flags.getOrElse("isa", "riscv32i")
    val defaultPc: Long = flags.get("defaultPc").fold(0x80000000L)(java.lang.Long.decode(_))
    val sim = flags.getOrElse("sim", "true").toBoolean
    val outDir = flags
      .get("outDir")
      .filter(_.nonEmpty)
      .fold(os.pwd / "build" / target / platform / isa / (if sim then "dpi" else "hw"))(os.Path(_, os.pwd))

    require(target == "core", s"only --target core is ported, got '$target'")
    require(platform == "yosys" || platform == "fpga", s"unknown platform '$platform'")

    val parameter = NzeaCoreParameter(isa, defaultPc, sim, mmioWindows)
    val scratch = os.temp.dir(prefix = "nzea-dump")
    try
      Elaborate.withLinked(
        NzeaCore.moduleName(parameter),
        scratch,
        cfg =>
          NzeaCore.main(
            Array("config", cfg.toString, "--isa", isa, "--defaultPc", defaultPc.toString, "--sim", sim.toString) ++
              mmioWindows.flatMap(window => Seq("--mmioRanges", window))
          ),
        cfg => NzeaCore.main(Array("design", cfg.toString))
      ): circuit =>
        Elaborate.verilogInto(
          circuit,
          outDir,
          if sim then dpiLowering else hwLowering
        )
        // The counters and the schema that describes them travel with the RTL they belong to: a simulator reads the
        // counters through VPI and interprets them through the schema.
        if sim then
          StatsRegs.emit(
            outDir,
            Seq(
              (ThroughputCounters.verilogModuleName(ThroughputCountersParameter()), CoreStats.throughputCounters),
              (BpCounters.verilogModuleName(BpCountersParameter()), CoreStats.bpCounters)
            )
          )
          StatSchema.emit(outDir.toString, NzeaCore.moduleName(parameter), CoreStats.schema)
    finally os.remove.all(scratch)

    println(s"elaborated $target:$isa for $platform into $outDir")

  /** `--flag value` pairs; every flag takes a value, so a missing one is an error rather than a silent default. */
  private def parse(args: List[String]): Map[String, String] = args match
    case Nil                                            => Map.empty
    case flag :: value :: rest if flag.startsWith("--") => parse(rest) + (flag.stripPrefix("--") -> value)
    case flag :: _ => throw new IllegalArgumentException(s"'$flag' expects a value\n$usage")
