package nzea_core.stats

import java.nio.file.{Files, Path, Paths}
import nzea_config.SynthPlatform
import nzea_config.core.CoreConfig
import nzea_core.CoreElaborate
import org.scalatest.flatspec.AnyFlatSpec

import scala.jdk.CollectionConverters._

/** Statistics contract tests: the schema validates, the declared counters are exactly the counters the core emits, and
  * the emitted schema file matches the RTL it travels with.
  */
class CoreStatsTest extends AnyFlatSpec {

  private implicit val config: CoreConfig = CoreConfig(
    isa = "riscv32i",
    defaultPc = 0x8000_0000L,
    sim = true
  )

  private val annotatedReg = """reg \[(\d+):0\] (stat_[A-Za-z0-9_]+) /\*verilator public_flat_rd\*/;""".r

  /** Elaborate the simulation core once and hand both artifacts (RTL text, schema file) to the checks. */
  private lazy val (rtlSources, schemaFile): (String, Path) = {
    val dir = Files.createTempDirectory("nzea-core-stats")
    CoreElaborate.elaborate(dir.toString, SynthPlatform.Yosys.firtoolOpts(sim = true))
    val sv = Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".sv"))
      .map(p => new String(Files.readAllBytes(p)))
      .mkString("\n")
    (sv, dir.resolve("NzeaCore.stats.toml"))
  }

  "schema" should "validate with unique names and resolvable references" in {
    CoreStats.schema.validate()
  }

  it should "cover the counters declared by name and region" in {
    val names = CoreStats.schema.counters.map(_.name)
    assert(names == names.distinct)
    assert(names.forall(_.startsWith("stat_")))
    assert(CoreStats.schema.regions.map(_.name) == Seq("throughput", "bp"))
    assert(
      CoreStats.schema.derived.filter(_.region == CoreStats.Bp).map(_.name) ==
        Seq("mispredict_rate", "taken_rate")
    )
  }

  it should "derive the ipc/cpi pair from the same numbers a run reports" in {
    val out = CoreStats.schema.evaluate(Map("stat_inst_commit" -> 676143L, "stat_cycle" -> 3111440L))
    val ipc = out("ipc").get
    assert(math.abs(ipc - 0.2173) < 5e-5, s"ipc = $ipc")
    assert(math.abs(out("cpi").get - 1.0 / ipc) < 1e-12)
  }

  it should "report n/a instead of 0 when a run has no cycles" in {
    val out = CoreStats.schema.evaluate(Map("stat_inst_commit" -> 0L, "stat_cycle" -> 0L))
    assert(out("ipc").isEmpty && out("cpi").isEmpty)
  }

  "emitted RTL" should "expose exactly the declared counters with the declared widths" in {
    val emitted = annotatedReg.findAllMatchIn(rtlSources).map(m => m.group(2) -> (m.group(1).toInt + 1)).toMap
    val declared = CoreStats.schema.counters.map(c => c.name -> c.width).toMap
    assert(emitted == declared, s"rtl=$emitted declared=$declared")
  }

  "emitted schema" should "list every counter and derived entry" in {
    assert(Files.exists(schemaFile), s"missing $schemaFile")
    val text = new String(Files.readAllBytes(schemaFile))
    assert(text.startsWith("schema = 1\ndesign = \"NzeaCore\"\n"), text)
    CoreStats.schema.counters.map(_.name).foreach(n => assert(text.contains(s"""name = "$n""""), n))
    CoreStats.schema.derived.map(_.name).foreach(n => assert(text.contains(s"""name = "$n""""), n))
    assert(text.contains("""expr = "1 / ipc""""), text)
  }

  it should "be found next to the RTL it describes" in {
    assert(Paths.get(schemaFile.toString).getFileName.toString == "NzeaCore.stats.toml")
    assert(rtlSources.contains("module NzeaCore("), "RTL did not elaborate")
  }

}
