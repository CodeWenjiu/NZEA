package nzea_core.back

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.int.{AluOp, AluSrc, BruOp, FuType, IntOp, MulOp}
import nzea_core.back.mem.{LsuOp, LsuSrc, MemOp}
import nzea_core.isa.Backend
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

case class BackElaborateTestParameter(dummy: Int) extends Parameter

given upickle.default.ReadWriter[BackElaborateTestParameter] = upickle.default.macroRW

class BackElaborateTestLayers(parameter: BackElaborateTestParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The decode result the middle hands to a backend, read the way the backends read it. */
class BackElaborateTestIO(parameter: BackElaborateTestParameter) extends HWBundle(parameter):
  val backend = Flipped(UInt(Backend.width))
  val op = Flipped(UInt(OpField.Width))
  val int_fu_type = Aligned(UInt(FuType.width))
  val alu_op = Aligned(UInt(AluOp.width))
  val alu_src = Aligned(UInt(AluSrc.width))
  val bru_op = Aligned(UInt(BruOp.width))
  val mul_op = Aligned(UInt(MulOp.width))
  val mem_op = Aligned(UInt(LsuOp.width))
  val mem_src = Aligned(UInt(LsuSrc.width))
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

    val isMem = io.backend === Backend.MEM.lit.U(Backend.width)

    // The int path unpacks its own window, and its FUs eat the low column straight from it.
    io.int_fu_type := IntOp.fuType(io.op)
    io.alu_op := IntOp.fuOp(io.op)
    io.alu_src := IntOp.fuSrc(io.op)
    io.bru_op := Opcode.take(io.op, BruOp.width)
    io.mul_op := Opcode.take(io.op, MulOp.width)

    // The mem path unpacks its own, which starts at bit 0 as well: the windows alias.
    val memOp = MemOp.op(io.op)
    io.mem_op := memOp
    io.mem_src := MemOp.src(io.op)
    io.is_store := isMem & LsuOp.isStore(memOp)
    io.is_load := isMem & LsuOp.isLoad(memOp)

/** Structural checks: one shared field sized by the longest window, and each backend's own column widths. */
object BackElaborateTest extends TestSuite:

  private val module = BackElaborateTestModule.moduleName(BackElaborateTestParameter(0))

  private lazy val sv = TestFlow.lower(
    module,
    cfg => BackElaborateTestModule.main(Array("config", cfg.toString, "--dummy", "0")),
    cfg => BackElaborateTestModule.main(Array("design", cfg.toString))
  )

  private lazy val ports = TestFlow.ports(sv, module)

  val tests = Tests:
    test("the shared field is the union width, the columns are each backend's own"):
      assert(sv.contains(s"module $module("))
      assert(
        ports == Map(
          "backend" -> Backend.width,
          "op" -> OpField.Width,
          "int_fu_type" -> FuType.width,
          "alu_op" -> IntOp.fuOp.width,
          "alu_src" -> IntOp.fuSrc.width,
          "bru_op" -> BruOp.width,
          "mul_op" -> MulOp.width,
          "mem_op" -> MemOp.op.width,
          "mem_src" -> MemOp.src.width,
          "is_store" -> 1,
          "is_load" -> 1
        )
      )
