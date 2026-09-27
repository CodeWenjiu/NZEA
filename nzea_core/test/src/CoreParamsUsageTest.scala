package nzea_core

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.{CoreConfig, CoreParams, OpProducer}
import nzea_core.back.int.IntOp
import nzea_core.back.mem.MemOp
import nzea_core.isa.Backend
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

case class CoreParamsUsageTestParameter(isa: String, defaultPc: Long) extends Parameter:
  /** The configuration the CLI pinned down; the resolver reads it, not the raw flags. */
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[CoreParamsUsageTestParameter] = upickle.default.macroRW

/** Where the backends are known, their declarations are stated once; nothing here is a width, only what each side says
  * it needs.
  */
object CoreParamsUsageTestDeclared:

  val declared: Seq[OpProducer] = Seq(
    OpProducer("int", Backend.INT.lit, IntOp.width),
    OpProducer("mem", Backend.MEM.lit, MemOp.width)
  )

class CoreParamsUsageTestLayers(parameter: CoreParamsUsageTestParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The ports are as wide as the contract says, and nothing in this class picks a width by hand. */
class CoreParamsUsageTestIO(parameter: CoreParamsUsageTestParameter) extends HWBundle(parameter):
  private val params = CoreParams(parameter.config, Backend.width, CoreParamsUsageTestDeclared.declared)

  val dispatch = Flipped(UInt(params.dispatchWidth))
  val op = Flipped(UInt(params.opWidth))
  val is_mem = Aligned(Bool())
  val alu_op = Aligned(UInt(IntOp.payloadWidth))
  val mem_op = Aligned(UInt(MemOp.payloadWidth))

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
    val params = CoreParams(parameter.config, Backend.width, CoreParamsUsageTestDeclared.declared)
    println(s"elaborating: ${params.report}")

    io.is_mem := io.dispatch === Backend.MEM.lit.U(params.dispatchWidth)
    io.alu_op := IntOp.payload(io.op)
    io.mem_op := MemOp.payload(io.op)

/** The usage: the resolved contract sizes the field, and each backend still unpacks its own window out of it. */
object CoreParamsUsageTest extends TestSuite:

  private val module = CoreParamsUsageTestModule.moduleName(
    CoreParamsUsageTestParameter("riscv32i", 0x80000000L)
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
    test("the resolved contract is what reaches the hardware"):
      assert(sv.contains(s"module $module("))
      assert(
        ports == Map(
          "dispatch" -> 1,
          "op" -> 13,
          "is_mem" -> 1,
          "alu_op" -> IntOp.payloadWidth,
          "mem_op" -> MemOp.payloadWidth
        )
      )
