package nzea_config

import nzea_core.BypassConfig
import nzea_core.isa.Isa
import nzea_rtl.{RtlFlow, StatScope}
import utest.*

/** The aggregate's contract: it carries each module's own config unchanged, derives the one thing it owns (the core
  * config), and round-trips through the config file the generator reads.
  */
object NzeaCoreParameterTest extends TestSuite:

  private val parameter =
    NzeaCoreParameter(
      Isa("riscv32im"),
      0x80000000L,
      RtlFlow.Yosys,
      Seq("0x10000000:8"),
      BypassConfig.writeback,
      StatScope.root
    )

  val tests = Tests:
    test("the aggregate derives the core config and keeps the modules' own"):
      assert(parameter.config.width == 32)
      assert(parameter.config.defaultPc == 0x80000000L)
      assert(parameter.bypass.writeback)
      assert(!parameter.flow.bridges)

    test("it round-trips through the config file"):
      val text = upickle.default.write(parameter)
      assert(upickle.default.read[NzeaCoreParameter](text) == parameter)
