package nzea_top

import nzea_core.config.{BpuLevel, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import utest.*

/** The top's parameter: it carries the design tree and each module's own config unchanged, and round-trips through the
  * config file the generator reads.
  */
object NzeaCoreParameterTest extends TestSuite:

    private val parameter =
        NzeaCoreParameter(
            TopConfig(CoreConfig(Isa("riscv32im"), 0x80000000L)),
            RtlFlow.Yosys,
            Seq("0x10000000:8"),
            BypassLevel.Commit,
            BpuLevel.Bimodal(128, 8),
            StatScope.root
        )

    val tests = Tests:
        test("the parameter carries the tree and keeps the modules' own"):
            assert(parameter.config.core.width == 32)
            assert(parameter.config.core.defaultPc == 0x80000000L)
            assert(parameter.bypass.commit)
            assert(parameter.bpu.predicted)
            assert(parameter.bpu.name == "bimodal:128:8")
            assert(!parameter.flow.bridges)

        test("it round-trips through the config file"):
            val text = upickle.default.write(parameter)
            assert(upickle.default.read[NzeaCoreParameter](text) == parameter)
