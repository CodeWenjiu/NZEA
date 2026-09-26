package nzea_cli

import nzea_core.{NzeaCore, NzeaCoreParameter}
import nzea_rtl.Elaborate

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
    require(sim, "the core's synthesis top (exposed buses) arrives with the tile; pass --sim true")
    require(platform == "yosys" || platform == "fpga", s"unknown platform '$platform'")

    val parameter = NzeaCoreParameter(isa, defaultPc, mmioWindows)
    val scratch = os.temp.dir(prefix = "nzea-dump")
    try
      Elaborate.withLinked(
        NzeaCore.moduleName(parameter),
        scratch,
        cfg =>
          NzeaCore.main(
            Array("config", cfg.toString, "--isa", isa, "--defaultPc", defaultPc.toString) ++
              mmioWindows.flatMap(window => Seq("--mmioRanges", window))
          ),
        cfg => NzeaCore.main(Array("design", cfg.toString))
      ): circuit =>
        Elaborate.verilogInto(
          circuit,
          outDir,
          Seq("--split-verilog", "-disable-all-randomization", "-strip-debug-info")
        )
    finally os.remove.all(scratch)

    println(s"elaborated $target:$isa for $platform into $outDir")

  /** `--flag value` pairs; every flag takes a value, so a missing one is an error rather than a silent default. */
  private def parse(args: List[String]): Map[String, String] = args match
    case Nil                                            => Map.empty
    case flag :: value :: rest if flag.startsWith("--") => parse(rest) + (flag.stripPrefix("--") -> value)
    case flag :: _ => throw new IllegalArgumentException(s"'$flag' expects a value\n$usage")
