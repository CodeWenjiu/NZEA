package nzea_rtl.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import utest.*

/** A local opcode table, so this test needs no nzea code: two sets of different widths aliasing in one field. */
enum OpcodeTestOp(val lit: Int) extends Opcode:
  case A extends OpcodeTestOp(1 << 0)
  case B extends OpcodeTestOp(1 << 1)
  case C extends OpcodeTestOp(1 << 2)

object OpcodeTestOp:
  val width: Int = Opcode.width(values)

enum OpcodeTestNarrow(val lit: Int) extends Opcode:
  case P extends OpcodeTestNarrow(0)
  case Q extends OpcodeTestNarrow(1)

object OpcodeTestNarrow:
  val width: Int = Opcode.width(values)

/** A local window over both sets, low-anchored as `[narrow | op]`. */
object OpcodeTestWindow:
  val op = OpColumn(0, OpcodeTestOp.width)
  val narrow = OpColumn(op.end, OpcodeTestNarrow.width)
  val width: Int = narrow.end

  def lit(narrow: OpcodeTestNarrow, op: OpcodeTestOp): Int =
    OpcodeTestWindow.narrow.lit(narrow) | OpcodeTestWindow.op.lit(op)

case class OpcodeTestParameter(dummy: Int) extends Parameter

given upickle.default.ReadWriter[OpcodeTestParameter] = upickle.default.macroRW

class OpcodeTestLayers(parameter: OpcodeTestParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class OpcodeTestIO(parameter: OpcodeTestParameter) extends HWBundle(parameter):
  val raw = Flipped(UInt(8))
  val wide = Aligned(UInt(OpcodeTestOp.width))
  val narrow = Aligned(UInt(OpcodeTestNarrow.width))
  val window = Flipped(UInt(OpcodeTestWindow.width))
  val window_op = Aligned(UInt(OpcodeTestOp.width))
  val window_narrow = Aligned(UInt(OpcodeTestNarrow.width))
  val constant = Aligned(UInt(6))

class OpcodeTestProbe(parameter: OpcodeTestParameter) extends DVBundle[OpcodeTestParameter, OpcodeTestLayers](parameter)

/** Slices two aliased encodings out of one field, unpacks a window, and materializes a member as a constant. */
@generator
object OpcodeTestModule extends Generator[OpcodeTestParameter, OpcodeTestLayers, OpcodeTestIO, OpcodeTestProbe]:
  override def moduleName(p: OpcodeTestParameter): String = "OpcodeTest"

  def architecture(parameter: OpcodeTestParameter) =
    val io = summon[Interface[OpcodeTestIO]]
    io.wide := Opcode.take(io.raw, OpcodeTestOp.width)
    io.narrow := Opcode.take(io.raw, OpcodeTestNarrow.width)
    io.window_op := OpcodeTestWindow.op(io.window)
    io.window_narrow := OpcodeTestWindow.narrow(io.window)
    io.constant := OpcodeTestOp.C.lit.U(6)

/** Structural checks: the width rule, the window pack/unpack pair, and the lowered field widths. */
object OpcodeTest extends TestSuite:

  private val module = OpcodeTestModule.moduleName(OpcodeTestParameter(0))

  private lazy val sv = TestFlow.lower(
    module,
    cfg => OpcodeTestModule.main(Array("config", cfg.toString, "--dummy", "0")),
    cfg => OpcodeTestModule.main(Array("design", cfg.toString))
  )

  private lazy val ports = TestFlow.ports(sv, module)

  val tests = Tests:
    test("width is the bits the largest literal needs"):
      assert(Opcode.width(OpcodeTestOp.values.toSeq) == 3)
      assert(Opcode.width(OpcodeTestNarrow.values.toSeq) == 1)

    test("a window composes and separates its columns"):
      assert(OpcodeTestWindow.width == 4)
      for narrow <- OpcodeTestNarrow.values; op <- OpcodeTestOp.values do
        val window = OpcodeTestWindow.lit(narrow, op)
        assert(((window >> OpcodeTestWindow.narrow.offset) & 0x1) == narrow.lit)
        assert(((window >> OpcodeTestWindow.op.offset) & 0x7) == op.lit)

    test("slices and member constants reach the lowered Verilog"):
      assert(sv.contains("module OpcodeTest("))
      assert(
        ports == Map(
          "raw" -> 8,
          "wide" -> OpcodeTestOp.width,
          "narrow" -> OpcodeTestNarrow.width,
          "window" -> OpcodeTestWindow.width,
          "window_op" -> OpcodeTestWindow.op.width,
          "window_narrow" -> OpcodeTestWindow.narrow.width,
          "constant" -> 6
        )
      )
      // OpcodeTestOp.C is 1 << 2, materialized at the width the caller asks for.
      assert(sv.contains("6'h4"))
