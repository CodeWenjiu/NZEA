package nzea_core.config

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.int.IntOp
import nzea_core.back.mem.MemOp
import nzea_core.isa.{Backend, Isa}
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

case class CoreParamsUsageTestParameter(isa: Isa, defaultPc: Long) extends Parameter:
    /** The configuration the CLI pinned down; the resolver reads it, not the raw flags. */
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[CoreParamsUsageTestParameter] = upickle.default.macroRW

/** Where the backends are known, their declarations are stated once; nothing here is a width, only what each side says
  * it needs. The int window is the configuration's, since the units it carries are.
  */
object CoreParamsUsageTestDeclared:

    def declared(isa: Isa): Seq[OpProducer] = Seq(
        OpProducer("int", Backend.INT.lit, IntOp.set(isa).width),
        OpProducer("mem", Backend.MEM.lit, MemOp.set(isa).width)
    )

/** The parameters the module and its IO agree on: the ISA decides both the contract and the windows inside it. */
private def testParams(parameter: CoreParamsUsageTestParameter): CoreParams =
    CoreParams(parameter.config, Backend.width, CoreParamsUsageTestDeclared.declared(parameter.config.isa))

class CoreParamsUsageTestLayers(parameter: CoreParamsUsageTestParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The ports are as wide as the contract says, and nothing in this class picks a width by hand. */
class CoreParamsUsageTestIO(parameter: CoreParamsUsageTestParameter) extends HWBundle(parameter):
    private val params = testParams(parameter)
    private val intOps = IntOp.set(parameter.config.isa)

    val dispatch = Flipped(UInt(params.dispatchWidth))
    val op = Flipped(UInt(params.opWidth))
    val is_mem = Aligned(Bool())
    val alu_op = Aligned(UInt(intOps.payloadWidth))
    val mem_op = Aligned(UInt(MemOp.set(parameter.config.isa).payloadWidth))

class CoreParamsUsageTestProbe(parameter: CoreParamsUsageTestParameter)
    extends DVBundle[CoreParamsUsageTestParameter, CoreParamsUsageTestLayers](parameter)

/** Resolving is pure and cheap, so every class that needs the contract resolves it from the parameter itself; the
  * numbers come from one function and nobody re-derives them.
  */
@generator
object CoreParamsUsageTestModule
    extends Generator[
        CoreParamsUsageTestParameter,
        CoreParamsUsageTestLayers,
        CoreParamsUsageTestIO,
        CoreParamsUsageTestProbe
    ]:
    override def moduleName(p: CoreParamsUsageTestParameter): String = "CoreParamsUsageTest"

    def architecture(parameter: CoreParamsUsageTestParameter) =
        val io = summon[Interface[CoreParamsUsageTestIO]]
        val params = testParams(parameter)
        val intOps = IntOp.set(parameter.config.isa)
        println(s"elaborating: ${params.report}")

        io.is_mem := io.dispatch === Backend.MEM.lit.U(params.dispatchWidth)
        io.alu_op := intOps.payload(io.op)
        io.mem_op := MemOp.set(parameter.config.isa).payload(io.op)

/** The configuration tree's contracts: the bypass ladder's one token and its predicates, the resolver's pure-function
  * checks, and the resolved contract as it reaches hardware.
  */
object ConfigTest extends TestSuite:

    /** riscv32i: no M units, so the int window is a payload column plus a two-bit tag for the three units that exist.
      */
    private val intOps = IntOp.set(CoreConfig(Isa("riscv32i"), 0x80000000L).isa)
    private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)
    private val declared = Seq(OpProducer("int", 0, 17), OpProducer("mem", 1, 9))

    private val module = CoreParamsUsageTestModule.moduleName(
        CoreParamsUsageTestParameter(Isa("riscv32i"), 0x80000000L)
    )

    private lazy val sv = TestFlow.lower(
        module,
        cfg =>
            CoreParamsUsageTestModule.main(
                Array("config", cfg.toString, "--isa", "riscv32i", "--defaultPc", "2147483648")
            ),
        cfg => CoreParamsUsageTestModule.main(Array("design", cfg.toString))
    )

    private lazy val ports = TestFlow.ports(sv, module)

    val tests = Tests:
        // ── The bypass ladder ──
        test("every level round-trips through the file and the token"):
            for level <- BypassLevel.values do
                assert(BypassLevel.parseOrThrow(level.name) == level)
                assert(upickle.default.read[BypassLevel](upickle.default.write(level)) == level)

        test("the flag reader takes the same token"):
            val reader = summon[TokensReader[BypassLevel]]
            for level <- BypassLevel.values do assert(reader.read(Seq("--bypass", level.name)) == Right(level))

        test("a deeper level implies the shallower link"):
            assert(!BypassLevel.None.commit)
            assert(!BypassLevel.Commit.execute)
            assert(BypassLevel.Commit.commit)
            assert(BypassLevel.Execute.commit && BypassLevel.Execute.execute)

        test("unknown levels are rejected, and the old spellings with them"):
            assert(BypassLevel.parse("wb").isLeft)
            assert(BypassLevel.parse("writeback").isLeft)
            assert(BypassLevel.parse("none") == Right(BypassLevel.None))
            assert(BypassLevel.parse("COMMIT") == Right(BypassLevel.Commit))
            assert(BypassLevel.parse("execute") == Right(BypassLevel.Execute))

        test("the report names the links that reach the read"):
            assert(BypassLevel.None.report == "none")
            assert(BypassLevel.Commit.report == "commit -> register read")
            assert(BypassLevel.Execute.report == "execute -> register read")

        // ── The resolver ──
        test("the longest window sizes the shared field"):
            val params = CoreParams(config, dispatchWidth = 1, declared)
            println(params.report)
            assert(params.opWidth == 17)
            assert(params.widestOp == "int")
            assert(params.dispatchWidth == 1)
            assert(params.dataWidth == 32)
            assert(params.pcWidth == 32)

        test("adding a wider producer moves the field for everyone"):
            val params = CoreParams(config, dispatchWidth = 2, declared :+ OpProducer("fp", 2, 25))
            println(params.report)
            assert(params.opWidth == 25)
            assert(params.widestOp == "fp")

        test("a declaration that breaks the contract fails before any hardware exists"):
            val outOfField =
                intercept[IllegalArgumentException](
                    CoreParams(config, dispatchWidth = 1, declared :+ OpProducer("fp", 2, 8))
                )
            println(outOfField.getMessage)
            assert(outOfField.getMessage.contains("dispatch id does not fit the 1-bit field: fp=2"))

            val duplicate =
                intercept[IllegalArgumentException](
                    CoreParams(config, dispatchWidth = 2, declared :+ OpProducer("int", 2, 8))
                )
            assert(duplicate.getMessage.contains("duplicate producer names: int, mem, int"))

            val empty = intercept[IllegalArgumentException](CoreParams(config, dispatchWidth = 1, Seq.empty))
            assert(empty.getMessage.contains("no op producer declared"))

        // ── The contract in hardware ──
        test("the resolved contract is what reaches the hardware"):
            assert(sv.contains(s"module $module("))
            assert(
                ports == Map(
                    "dispatch" -> 1,
                    "op" -> intOps.width,
                    "is_mem" -> 1,
                    "alu_op" -> intOps.payloadWidth,
                    "mem_op" -> MemOp.set(CoreConfig(Isa("riscv32i"), 0x80000000L).isa).payloadWidth
                )
            )
