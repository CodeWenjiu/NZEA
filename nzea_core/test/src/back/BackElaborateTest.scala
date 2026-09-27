package nzea_core.back

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.int.{AluOp, BruOp, IntOp, MulOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.Backend
import nzea_config.core.CoreConfig
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

case class BackElaborateTestParameter(isa: String) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, 0x80000000L)

given upickle.default.ReadWriter[BackElaborateTestParameter] = upickle.default.macroRW

class BackElaborateTestLayers(parameter: BackElaborateTestParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The decode result the middle hands to a backend, read the way the backends read it. */
class BackElaborateTestIO(parameter: BackElaborateTestParameter) extends HWBundle(parameter):
  private val intOps = IntOp.set(parameter.config.isaConfig)

  val backend = Flipped(UInt(Backend.width))
  val op = Flipped(UInt(OpWindow.width(parameter.config)))
  val int_fu_type = Aligned(UInt(intOps.tagWidth))
  val alu_op = Aligned(UInt(AluOp.width))
  val bru_op = Aligned(UInt(BruOp.width))
  val mul_op = Aligned(UInt(MulOp.width))
  val mem_op = Aligned(UInt(LsuOp.width))
  val is_store = Aligned(Bool())
  val is_load = Aligned(Bool())

class BackElaborateTestProbe(parameter: BackElaborateTestParameter)
    extends DVBundle[BackElaborateTestParameter, BackElaborateTestLayers](parameter)

/** Dispatches on `backend`, then unpacks the window each side owns — no consumer outside a backend reads its layout. */
@generator
object BackElaborateTestModule
    extends Generator[BackElaborateTestParameter, BackElaborateTestLayers, BackElaborateTestIO, BackElaborateTestProbe]:
  override def moduleName(p: BackElaborateTestParameter): String = "BackElaborateTest"

  def architecture(parameter: BackElaborateTestParameter) =
    val io = summon[Interface[BackElaborateTestIO]]

    val intOps = IntOp.set(parameter.config.isaConfig)
    val isMem = io.backend === Backend.MEM.lit.U(Backend.width)

    // The int path unpacks its own window, and its FUs eat the low column straight from it.
    io.int_fu_type := intOps.tag(io.op)
    io.alu_op := intOps.payload(io.op)
    io.bru_op := Encoding.take(io.op, BruOp.width)
    io.mul_op := Encoding.take(io.op, MulOp.width)

    // The mem path unpacks its own, which starts at bit 0 as well: the windows alias.
    val memOp = MemOp.payload(io.op)
    io.mem_op := memOp
    io.is_store := isMem & LsuOp.isStore(memOp)
    io.is_load := isMem & LsuOp.isLoad(memOp)

/** Structural checks: one shared field sized by the longest window, and each backend's own column widths. */
object BackElaborateTest extends TestSuite:

  private val isa = "riscv32i"
  private val config = CoreConfig(isa, 0x80000000L)
  private val intOps = IntOp.set(config.isaConfig)
  private val module = BackElaborateTestModule.moduleName(BackElaborateTestParameter(isa))

  private lazy val sv = TestFlow.lower(
    module,
    cfg => BackElaborateTestModule.main(Array("config", cfg.toString, "--isa", isa)),
    cfg => BackElaborateTestModule.main(Array("design", cfg.toString))
  )

  private lazy val ports = TestFlow.ports(sv, module)

  val tests = Tests:
    test("the shared field is the union width, the columns are each backend's own"):
      assert(sv.contains(s"module $module("))
      assert(
        ports == Map(
          "backend" -> Backend.width,
          "op" -> OpWindow.width(config),
          "int_fu_type" -> intOps.tagWidth,
          "alu_op" -> intOps.payloadWidth,
          "bru_op" -> BruOp.width,
          "mul_op" -> MulOp.width,
          "mem_op" -> MemOp.payloadWidth,
          "is_store" -> 1,
          "is_load" -> 1
        )
      )

    test("the int window follows the configuration: three units without M, five with it"):
      val withM = IntOp.set(CoreConfig("riscv32im", 0x80000000L).isaConfig)
      assert(intOps.cases.size == 3)
      assert(intOps.tagWidth == 2)
      assert(withM.cases.size == 5)
      assert(withM.tagWidth == 3)
      assert(intOps.width == withM.width - 1)
