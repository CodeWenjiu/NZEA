package nzea_rtl.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import utest.*

case class ArithTestParameter(width: Int) extends Parameter:
    require(width >= 3, s"width must be at least 3, got $width")

given upickle.default.ReadWriter[ArithTestParameter] = upickle.default.macroRW

class ArithTestLayers(parameter: ArithTestParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ArithTestIO(parameter: ArithTestParameter) extends HWBundle(parameter):
    private val width = parameter.width
    val x = Flipped(UInt(width))
    val plus1 = Aligned(UInt(width))
    val plus2 = Aligned(UInt(width))
    val plus4 = Aligned(UInt(width))

class ArithTestProbe(parameter: ArithTestParameter) extends DVBundle[ArithTestParameter, ArithTestLayers](parameter)

/** Exercises the constant adders; elaborated (and lowered to Verilog) by the test below. */
@generator
object ArithTestModule extends Generator[ArithTestParameter, ArithTestLayers, ArithTestIO, ArithTestProbe]:
    override def moduleName(p: ArithTestParameter): String = s"ArithTest_w${p.width}"

    def architecture(parameter: ArithTestParameter) =
        val io = summon[Interface[ArithTestIO]]
        io.plus1 := constAdder(io.x, 1, parameter.width)
        io.plus2 := constAdder(io.x, 2, parameter.width)
        io.plus4 := constAdder(io.x, 4, parameter.width)

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

/** Structural checks for the stdlib helpers: the constant adders and the selection helpers.
  *
  * The helpers are elaborated through the real flow (`config` -> `design` -> `firtool`), so this also covers the
  * elaboration path itself and proves the emitted FIRRTL verifies. Behavioural coverage comes from the core difftest —
  * every fetch crosses `constAdder(pc, 4)` — and the anchors below pin the host-side rules.
  */
object HelpersTest extends TestSuite:

    /** Elaborate `ArithTestModule` and lower it to Verilog. */
    private def elaboratedAdders(width: Int): String =
        val module = ArithTestModule.moduleName(ArithTestParameter(width))
        TestFlow.lower(
            module,
            cfg => ArithTestModule.main(Array("config", cfg.toString, "--width", width.toString)),
            cfg => ArithTestModule.main(Array("design", cfg.toString))
        )

    /** Elaborate `MuxTestModule` and lower it to Verilog. */
    private def elaboratedMuxes(width: Int, variants: Int): String =
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
        test("the constant adders elaborate into verifiable FIRRTL with the expected interface"):
            val sv = elaboratedAdders(width = 32)
            assert(sv.contains("module ArithTest_w32("))
            for port <- Seq("x", "plus1", "plus2", "plus4") do assert(sv.contains(port))

        test("constAdderAnchor"):
            assert(constAdderAnchor(1) == 0)
            assert(constAdderAnchor(2) == 1)
            assert(constAdderAnchor(4) == 2)
            assert(constAdderAnchor(BigInt(1) << 63) == 63)
            intercept[IllegalArgumentException](constAdderAnchor(0))
            intercept[IllegalArgumentException](constAdderAnchor(3))
            intercept[IllegalArgumentException](constAdderAnchor(-4))

        test("helpers elaborate into verifiable FIRRTL with the expected interface"):
            val sv = elaboratedMuxes(width = 8, variants = 2)
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
