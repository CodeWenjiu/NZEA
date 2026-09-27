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

enum EncodingTestNarrow extends Encoding.Sequential:
  case P
  case Q

object EncodingTestNarrow:
  val width: Int = Encoding.width(values)

/** A local union over both sets: each case carries one of them, and the case's tag sits above the payload. Its payload
  * width is asked with a configuration; this toy answers the same for every one, which is what `Unit` is here for.
  */
enum EncodingTestUnion extends Encoding.Union[Unit]:
  case Wide(payload: EncodingTestOp) extends EncodingTestUnion
  case Narrow(payload: EncodingTestNarrow) extends EncodingTestUnion

  def childWidth(config: Unit): Int = this match
    case Wide(_)   => EncodingTestOp.width
    case Narrow(_) => EncodingTestNarrow.width

object EncodingTestUnion extends Encoding.UnionSet[EncodingTestUnion, Unit]:
  val config: Unit = ()
  val cases: Seq[EncodingTestUnion] = Seq(Wide(EncodingTestOp.A), Narrow(EncodingTestNarrow.P))

/** A local aggregate: two fields sharing one value, the narrow one low-anchored. */
object EncodingTestAggregate extends Encoding.Aggregate:
  val fields: Seq[(String, Int)] = Seq("narrow" -> EncodingTestNarrow.width, "op" -> EncodingTestOp.width)

case class EncodingTestParameter(dummy: Int) extends Parameter

given upickle.default.ReadWriter[EncodingTestParameter] = upickle.default.macroRW

class EncodingTestLayers(parameter: EncodingTestParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class EncodingTestIO(parameter: EncodingTestParameter) extends HWBundle(parameter):
  val raw = Flipped(UInt(8))
  val wide = Aligned(UInt(EncodingTestOp.width))
  val narrow = Aligned(UInt(EncodingTestNarrow.width))
  val variant = Flipped(UInt(EncodingTestUnion.width))
  val union_payload = Aligned(UInt(EncodingTestUnion.payloadWidth))
  val union_tag = Aligned(UInt(EncodingTestUnion.tagWidth))
  val union_wide = Aligned(Bool())
  val agg = Flipped(UInt(EncodingTestAggregate.width))
  val agg_narrow = Aligned(UInt(EncodingTestNarrow.width))
  val agg_op = Aligned(UInt(EncodingTestOp.width))
  val constant = Aligned(UInt(6))
  val sel = Flipped(UInt(EncodingTestOp.width))
  val muxed = Aligned(UInt(6))

class EncodingTestProbe(parameter: EncodingTestParameter)
    extends DVBundle[EncodingTestParameter, EncodingTestLayers](parameter)

/** Slices two aliased encodings out of one field, unpacks a union, reads an aggregate's fields, and materializes a
  * member as a constant.
  */
@generator
object EncodingTestModule
    extends Generator[EncodingTestParameter, EncodingTestLayers, EncodingTestIO, EncodingTestProbe]:
  override def moduleName(p: EncodingTestParameter): String = "EncodingTest"

  def architecture(parameter: EncodingTestParameter) =
    val io = summon[Interface[EncodingTestIO]]
    io.wide := Encoding.take(io.raw, EncodingTestOp.width)
    io.narrow := Encoding.take(io.raw, EncodingTestNarrow.width)
    io.union_payload := EncodingTestUnion.payload(io.variant)
    io.union_tag := EncodingTestUnion.tag(io.variant)
    io.union_wide := EncodingTestUnion.isCase(EncodingTestUnion.Wide(EncodingTestOp.A), io.variant)
    io.agg_narrow := EncodingTestAggregate.read("narrow", io.agg)
    io.agg_op := EncodingTestAggregate.read("op", io.agg)
    io.constant := EncodingTestOp.C.lit.U(6)
    io.muxed := EncodingTestOp.mux1H(io.sel, op => op.lit.U(6), 6)

/** Structural checks: the width rule, the union and aggregate layouts, and the lowered field widths. */
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

    test("a union puts the payload under the case's tag, and derives its codes"):
      assert(EncodingTestUnion.payloadWidth == EncodingTestOp.width)
      assert(EncodingTestUnion.tagWidth == 1)
      assert(EncodingTestUnion.width == EncodingTestOp.width + 1)
      assert(EncodingTestUnion.lit(EncodingTestUnion.Wide(EncodingTestOp.C)) == EncodingTestOp.C.lit)
      assert(EncodingTestUnion.lit(EncodingTestUnion.Narrow(EncodingTestNarrow.Q)) == ((1 << EncodingTestOp.width) | 1))
      assert(EncodingTestUnion.tagOf(EncodingTestUnion.Narrow(EncodingTestNarrow.P)) == 1)

    test("an aggregate lays its fields end to end and derives their columns"):
      assert(EncodingTestAggregate.offset("narrow") == 0)
      assert(EncodingTestAggregate.offset("op") == EncodingTestNarrow.width)
      assert(EncodingTestAggregate.width == EncodingTestNarrow.width + EncodingTestOp.width)
      assert(EncodingTestAggregate.lit("op", EncodingTestOp.C) == EncodingTestOp.C.lit << EncodingTestNarrow.width)
      // Composition and the slicing the hardware does are the same map: a code lands where its field reads it back.
      for narrow <- EncodingTestNarrow.values; op <- EncodingTestOp.values do
        val field = EncodingTestAggregate.lit("narrow", narrow) | EncodingTestAggregate.lit("op", op)
        assert(((field >> EncodingTestAggregate.offset("narrow")) & 0x1) == narrow.lit)
        assert(((field >> EncodingTestAggregate.offset("op")) & 0x7) == op.lit)

    test("slices and member constants reach the lowered Verilog"):
      assert(sv.contains("module EncodingTest("))
      assert(
        ports == Map(
          "raw" -> 8,
          "wide" -> EncodingTestOp.width,
          "narrow" -> EncodingTestNarrow.width,
          "variant" -> EncodingTestUnion.width,
          "union_payload" -> EncodingTestUnion.payloadWidth,
          "union_tag" -> EncodingTestUnion.tagWidth,
          "union_wide" -> 1,
          "agg" -> EncodingTestAggregate.width,
          "agg_narrow" -> EncodingTestAggregate.fieldWidth("narrow"),
          "agg_op" -> EncodingTestAggregate.fieldWidth("op"),
          "constant" -> 6,
          "sel" -> EncodingTestOp.width,
          "muxed" -> 6
        )
      )
      // EncodingTestOp.C is 1 << 2, materialized at the width the caller asks for.
      assert(sv.contains("6'h4"))
