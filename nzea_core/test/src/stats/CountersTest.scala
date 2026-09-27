package nzea_core.stats

import nzea_config.RtlFlow
import nzea_config.core.{BypassConfig, Isa}
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.middle.{Middle, MiddleParameter}
import nzea_rtl.{StatCounter, StatsRegs, TestFlow}
import utest.*

/** The contract between a counter declaration, the black box text it produces and the wrapper that instantiates it: all
  * three name the same ports, and a simulator addresses the same registers the schema declares.
  */
object CountersTest extends TestSuite:

  private lazy val middle =
    Middle.moduleName(MiddleParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Verilator, BypassConfig.writeback))

  private lazy val middleSv = TestFlow.lower(
    middle,
    cfg =>
      Middle.main(
        Array(
          "config",
          cfg.toString,
          "--isa",
          "riscv32i",
          "--defaultPc",
          "2147483648",
          "--flow",
          "verilator",
          "--bypass",
          "writeback"
        )
      ),
    cfg => Middle.main(Array("design", cfg.toString))
  )

  private lazy val intPath = IntPath.moduleName(IntPathParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Verilator))

  private lazy val intPathSv = TestFlow.lower(
    intPath,
    cfg =>
      IntPath.main(
        Array("config", cfg.toString, "--isa", "riscv32i", "--defaultPc", "2147483648", "--flow", "verilator")
      ),
    cfg => IntPath.main(Array("design", cfg.toString))
  )

  /** Port names the instance connects. */
  private def connectedPorts(sv: String, moduleName: String): Set[String] =
    val start = sv.indexOf(s"$moduleName stats (")
    require(start >= 0, s"no $moduleName instance in the Verilog")
    val block = sv.substring(start, sv.indexOf(");", start))
    """\.(\w+)\s*\(""".r.findAllMatchIn(block).map(_.group(1)).toSet

  /** Port names the black box declares. */
  private def declaredPorts(moduleName: String, counters: Seq[StatCounter]): Set[String] =
    val text = StatsRegs.verilog(moduleName, counters)
    val start = text.indexOf(s"module $moduleName(")
    val block = text.substring(start, text.indexOf(");", start))
    """(?:input|output)\s*(?:\[\d+:\d+\]\s*)?(\w+)""".r.findAllMatchIn(block).map(_.group(1)).toSet

  val tests = Tests:
    test("the schema is valid and every counter is as wide as the wrappers' ports"):
      CoreStats.schema.validate()
      for c <- CoreStats.schema.counters do
        require(
          c.width == StatsRegs.Width,
          s"${c.name} is ${c.width} bits wide, but the wrapper's ports are ${StatsRegs.Width}"
        )

    test("the middle's throughput counters connect exactly the ports the black box declares"):
      assert(
        connectedPorts(middleSv, "ThroughputCounters") ==
          declaredPorts(
            ThroughputCounters.verilogModuleName(ThroughputCountersParameter()),
            CoreStats.throughputCounters
          )
      )

    test("the int path's branch counters connect exactly the ports the black box declares"):
      assert(
        connectedPorts(intPathSv, "BpCounters") ==
          declaredPorts(BpCounters.verilogModuleName(BpCountersParameter()), CoreStats.bpCounters)
      )
