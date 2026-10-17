package nzea_fpga

import java.lang.foreign.Arena

import utest.*

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_fpga.ip.ClockOut
import nzea_rtl.{Elaborate, TestFlow}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The probe's shape, chosen by its kind: 0 asks for two grids and materializes, 1 asks for the oscillator's own rate,
  * 2 asks for more grids than one primitive serves.
  */
private case class ClocksProbeParameter(kind: Int) extends Parameter

private given upickle.default.ReadWriter[ClocksProbeParameter] = upickle.default.macroRW

private class ClocksProbeLayers(parameter: ClocksProbeParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

private class ClocksProbeIO(parameter: ClocksProbeParameter) extends HWBundle(parameter):
    val oscillator = Flipped(Clock())
    val reset = Flipped(Bool())
    val a = Aligned(Clock())
    val b = Aligned(Clock())

private class ClocksProbeProbe(parameter: ClocksProbeParameter)
    extends DVBundle[ClocksProbeParameter, ClocksProbeLayers](parameter)

@generator
private object ClocksProbe extends Generator[ClocksProbeParameter, ClocksProbeLayers, ClocksProbeIO, ClocksProbeProbe]:
    override def moduleName(parameter: ClocksProbeParameter): String = s"ClocksProbe_${parameter.kind}"

    def architecture(parameter: ClocksProbeParameter) =
        val io = summon[Interface[ClocksProbeIO]]
        parameter.kind match
            case 1 =>
                val _ = Clocks.clock(ClockOut(50.0, 0.0))
                val clockingWizard = Clocks.materialize(50.0, io.oscillator, io.reset)
            case 2 =>
                for i <- 0 until 8 do Clocks.clock(ClockOut(100.0 + 10.0 * i, 0.0))
                val clockingWizard = Clocks.materialize(50.0, io.oscillator, io.reset)
            case _ =>
                val a = Clocks.clock(ClockOut(200.0, 0.0))
                assert(Clocks.clock(ClockOut(200.0, 0.0)) eq a)
                val b = Clocks.clock(ClockOut(400.0, 90.0))
                assert(Clocks.planned == Seq(ClockOut(200.0, 0.0), ClockOut(400.0, 90.0)))
                assert(Clocks.outputName(ClockOut(200.0, 0.0)) == s"clk_out1_${Clocks.ipName}")
                assert(Clocks.outputName(ClockOut(400.0, 90.0)) == s"clk_out2_${Clocks.ipName}")
                val clockingWizard = Clocks.materialize(50.0, io.oscillator, io.reset)
                io.a := a
                io.b := b

/** What [[Clocks]] promises, pinned where a design would see it: one wire per grid however often it is asked for, one
  * wizard for them all, the plan the asks state, and the two asks that are refused instead of built (the oscillator's
  * own rate, and a plan past one primitive's outputs).
  */
object ClocksProbeTest extends TestSuite:

    private def elaborate(kind: Int)(lower: os.Path => String): String =
        val scratch = os.temp.dir(prefix = "nzea-clocks-probe")
        try
            // A stale probe module from an earlier run would count as pre-existing to `withLinked`'s working-directory
            // diffing, and the run would then look as if it had produced nothing.
            os.list(os.pwd)
                .filter(path => path.ext == "mlirbc" && path.last.startsWith("ClocksProbe"))
                .foreach(os.remove(_))
            Elaborate.withLinked(
                ClocksProbe.moduleName(ClocksProbeParameter(kind)),
                scratch,
                cfg => ClocksProbe.main(Array("config", cfg.toString, "--kind", kind.toString)),
                cfg => ClocksProbe.main(Array("design", cfg.toString))
            )(lower)
        finally os.remove.all(scratch)

    val tests = Tests:

        test("one wire per grid, one wizard for them, and the names the plan gives them"):
            val sv = elaborate(0)(Elaborate.verilog(_, TestFlow.loweringOptions))
            println(s"--- clocks probe ---\n$sv\n--- probe end ---")
            assert(sv.split(s"${Clocks.ipName} clockingWizard").length - 1 == 1)
            assert(sv.contains(".clk_out1 (a)"))
            assert(sv.contains(".clk_out2 (b)"))

        test("the oscillator's own rate is refused, not built as a second net at it"):
            val failure = intercept[IllegalArgumentException](elaborate(1)(_ => ""))
            assert(failure.getMessage.contains("oscillator's own"))

        test("a plan past one primitive's outputs is refused, not split across generators"):
            val failure = intercept[IllegalArgumentException](elaborate(2)(_ => ""))
            assert(failure.getMessage.contains("a second wizard is not built yet"))
