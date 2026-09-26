package nzea_rtl.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import utest.*

case class MuxTestParameter(width: Int, variants: Int) extends Parameter:
  require(width >= 1, s"width must be positive, got $width")
  require(variants >= 1, s"variants must be positive, got $variants")

given upickle.default.ReadWriter[MuxTestParameter] = upickle.default.macroRW

class MuxTestLayers(parameter: MuxTestParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class MuxTestIO(parameter: MuxTestParameter) extends HWBundle(parameter):
  val sel = Flipped(UInt(parameter.variants))
  val key = Flipped(UInt(2))
  val cond = Flipped(Bool())
  val a = Flipped(Bits(parameter.width))
  val b = Flipped(Bits(parameter.width))
  val oneHot = Aligned(Bits(parameter.width))
  val caseOut = Aligned(Bits(parameter.width))
  val lookupOut = Aligned(Bits(parameter.width))
  val filled = Aligned(Bits(parameter.width))

class MuxTestProbe(parameter: MuxTestParameter) extends DVBundle[MuxTestParameter, MuxTestLayers](parameter)

/** Exercises every selection helper; elaborated (and lowered to Verilog) by the test below. */
@generator
object MuxTestModule extends Generator[MuxTestParameter, MuxTestLayers, MuxTestIO, MuxTestProbe]:
  override def moduleName(p: MuxTestParameter): String = s"MuxTest_w${p.width}_v${p.variants}"

  def architecture(parameter: MuxTestParameter) =
    val io = summon[Interface[MuxTestIO]]
    io.oneHot := mux1H(io.sel, Seq(io.a, io.b), parameter.width)
    io.caseOut := muxCase(io.a, Seq((io.cond, io.b), (io.sel.asBits.bit(0), io.a)))
    io.lookupOut := muxLookup(io.key, io.b)(Seq((1.U(2), io.a), (2.U(2), io.b)))
    io.filled := fill(parameter.width, io.cond)

/** Structural checks for the selection helpers.
  *
  * The helpers are elaborated through the real flow (`config` -> `design` -> `firtool`), so this also covers the
  * elaboration path itself and proves the emitted FIRRTL verifies. Behavioural coverage comes from module testbenches
  * and the core difftest.
  */
object MuxTest extends TestSuite:

  /** Elaborate `MuxTestModule` and lower it to Verilog. */
  private def elaborated(width: Int, variants: Int): String =
    val module = MuxTestModule.moduleName(MuxTestParameter(width, variants))
    TestFlow.lower(
      module,
      cfg =>
        MuxTestModule.main(
          Array("config", cfg.toString, "--width", width.toString, "--variants", variants.toString)
        ),
      cfg => MuxTestModule.main(Array("design", cfg.toString))
    )

  val tests = Tests:
    test("helpers elaborate into verifiable FIRRTL with the expected interface"):
      val sv = elaborated(width = 8, variants = 2)
      assert(sv.contains("module MuxTest_w8_v2("))
      for port <- Seq("sel", "key", "cond", "a", "b", "oneHot", "caseOut", "lookupOut", "filled") do
        assert(sv.contains(port))

    test("log2Ceil"):
      assert(log2Ceil(1) == 0)
      assert(log2Ceil(2) == 1)
      assert(log2Ceil(3) == 2)
      assert(log2Ceil(4) == 2)
      assert(log2Ceil(5) == 3)
      assert(log2Ceil(1024) == 10)
      assert(log2Ceil(1025) == 11)
