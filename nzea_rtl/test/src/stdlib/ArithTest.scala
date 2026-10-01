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

/** Structural checks for the constant adders.
  *
  * The helpers are elaborated through the real flow (`config` -> `design` -> `firtool`), so this also covers the
  * elaboration path itself and proves the emitted FIRRTL verifies. Behavioural coverage comes from the core difftest —
  * every fetch crosses `constAdder(pc, 4)` — and the anchors below pin the host-side rule.
  */
object ArithTest extends TestSuite:

    /** Elaborate `ArithTestModule` and lower it to Verilog. */
    private def elaborated(width: Int): String =
        val module = ArithTestModule.moduleName(ArithTestParameter(width))
        TestFlow.lower(
            module,
            cfg => ArithTestModule.main(Array("config", cfg.toString, "--width", width.toString)),
            cfg => ArithTestModule.main(Array("design", cfg.toString))
        )

    val tests = Tests:
        test("the constant adders elaborate into verifiable FIRRTL with the expected interface"):
            val sv = elaborated(width = 32)
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
