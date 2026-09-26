package nzea_rtl.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import utest.*

/** A local encoding set, so this test needs no nzea code: two encodings of different widths aliasing in one field. */
enum EncodingTestOp extends Encoding.OneHot:
  case A
  case B
  case C

object EncodingTestOp extends OneHotSet[EncodingTestOp]:
  def variants: Seq[EncodingTestOp] = values.toSeq
  val width: Int = Encoding.width(values)

enum EncodingTestNarrow extends Encoding.Sequential:
  case P
  case Q

object EncodingTestNarrow:
  val width: Int = Encoding.width(values)

/** A local window over both sets, low-anchored as `[narrow | op]`. */
object EncodingTestWindow:
  val op = Encoding.Column(0, EncodingTestOp.width)
  val narrow = Encoding.Column(op.end, EncodingTestNarrow.width)
  val width: Int = narrow.end

  def lit(narrow: EncodingTestNarrow, op: EncodingTestOp): Int =
    EncodingTestWindow.narrow.lit(narrow) | EncodingTestWindow.op.lit(op)

case class EncodingTestParameter(dummy: Int) extends Parameter

given upickle.default.ReadWriter[EncodingTestParameter] = upickle.default.macroRW

class EncodingTestLayers(parameter: EncodingTestParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class EncodingTestIO(parameter: EncodingTestParameter) extends HWBundle(parameter):
  val raw = Flipped(UInt(8))
  val wide = Aligned(UInt(EncodingTestOp.width))
  val narrow = Aligned(UInt(EncodingTestNarrow.width))
  val window = Flipped(UInt(EncodingTestWindow.width))
  val window_op = Aligned(UInt(EncodingTestOp.width))
  val window_narrow = Aligned(UInt(EncodingTestNarrow.width))
  val constant = Aligned(UInt(6))
  val sel = Flipped(UInt(EncodingTestOp.width))
  val muxed = Aligned(UInt(6))

class EncodingTestProbe(parameter: EncodingTestParameter)
    extends DVBundle[EncodingTestParameter, EncodingTestLayers](parameter)

/** Slices two aliased encodings out of one field, unpacks a window, and materializes a member as a constant. */
@generator
object EncodingTestModule
    extends Generator[EncodingTestParameter, EncodingTestLayers, EncodingTestIO, EncodingTestProbe]:
  override def moduleName(p: EncodingTestParameter): String = "EncodingTest"

  def architecture(parameter: EncodingTestParameter) =
    val io = summon[Interface[EncodingTestIO]]
    io.wide := Encoding.take(io.raw, EncodingTestOp.width)
    io.narrow := Encoding.take(io.raw, EncodingTestNarrow.width)
    io.window_op := EncodingTestWindow.op(io.window)
    io.window_narrow := EncodingTestWindow.narrow(io.window)
    io.constant := EncodingTestOp.C.lit.U(6)
    io.muxed := EncodingTestOp.mux1H(io.sel, op => op.lit.U(6), 6)

/** Structural checks: the width rule, the window pack/unpack pair, and the lowered field widths. */
object EncodingTest extends TestSuite:

  private val module = EncodingTestModule.moduleName(EncodingTestParameter(0))

  private lazy val sv = TestFlow.lower(
    module,
    cfg => EncodingTestModule.main(Array("config", cfg.toString, "--dummy", "0")),
    cfg => EncodingTestModule.main(Array("design", cfg.toString))
  )

  private lazy val ports = TestFlow.ports(sv, module)

  val tests = Tests:
    test("width is the bits the largest literal needs"):
      assert(Encoding.width(EncodingTestOp.values.toSeq) == 3)
      assert(Encoding.width(EncodingTestNarrow.values.toSeq) == 1)

    test("the traits derive the literals from the declaration order"):
      // What `mux1H` relies on: bit `i` selects variant `i`, which is also what `values` enumerates.
      assert(EncodingTestOp.values.toSeq.map(_.lit) == Seq(1, 2, 4))
      assert(EncodingTestOp.variants.zipWithIndex.forall((op, i) => op.lit == 1 << i))
      assert(EncodingTestNarrow.values.toSeq.map(_.lit) == Seq(0, 1))

    test("a window composes and separates its columns"):
      assert(EncodingTestWindow.width == 4)
      for narrow <- EncodingTestNarrow.values; op <- EncodingTestOp.values do
        val window = EncodingTestWindow.lit(narrow, op)
        assert(((window >> EncodingTestWindow.narrow.offset) & 0x1) == narrow.lit)
        assert(((window >> EncodingTestWindow.op.offset) & 0x7) == op.lit)

    test("slices and member constants reach the lowered Verilog"):
      assert(sv.contains("module EncodingTest("))
      assert(
        ports == Map(
          "raw" -> 8,
          "wide" -> EncodingTestOp.width,
          "narrow" -> EncodingTestNarrow.width,
          "window" -> EncodingTestWindow.width,
          "window_op" -> EncodingTestWindow.op.width,
          "window_narrow" -> EncodingTestWindow.narrow.width,
          "constant" -> 6,
          "sel" -> EncodingTestOp.width,
          "muxed" -> 6
        )
      )
      // EncodingTestOp.C is 1 << 2, materialized at the width the caller asks for.
      assert(sv.contains("6'h4"))
