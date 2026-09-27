package nzea_rtl

import mainargs.TokensReader
import utest.*

/** The flow's three contracts: the flag token, the config file's spelling of it, and what each flow says about the
  * design. The flag and the file are the same token on purpose — one spelling for a flow everywhere.
  */
object RtlFlowTest extends TestSuite:

  val tests = Tests:
    test("a segment round-trips through the flag and the config file"):
      for flow <- RtlFlow.values do
        assert(RtlFlow.fromString(flow.segment) == Right(flow))
        assert(upickle.default.read[String](upickle.default.write(flow)) == flow.segment)
        assert(upickle.default.read[RtlFlow](upickle.default.write(flow)) == flow)

    test("the flag reader takes the same segment"):
      val reader = summon[TokensReader[RtlFlow]]
      for flow <- RtlFlow.values do assert(reader.read(Seq("--flow", flow.segment)) == Right(flow))

    test("case does not matter, an unknown flow is rejected"):
      assert(RtlFlow.fromString("YOSYS") == Right(RtlFlow.Yosys))
      assert(RtlFlow.fromString("iverilog").isLeft)

    test("only the flow that reads the design through DPI wants bridges and counters"):
      assert(RtlFlow.Verilator.bridges && RtlFlow.Verilator.counters)
      assert(!RtlFlow.Yosys.bridges && !RtlFlow.Yosys.counters)
