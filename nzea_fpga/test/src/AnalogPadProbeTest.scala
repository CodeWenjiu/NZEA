package nzea_fpga

import java.lang.foreign.Arena

import utest.*

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.{Elaborate, TestFlow}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The probe's pad buffer: the shape a DDR3 pad has — fabric-side input and output beside an analog pin, whose real
  * body would be an `IOBUF`/`IOBUFDS`. The probe needs no body: what it checks is the port, not the pad.
  */
private case class PadBufParameter(width: Int) extends Parameter

private given upickle.default.ReadWriter[PadBufParameter] = upickle.default.macroRW

private class PadBufLayers(parameter: PadBufParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

private class PadBufIO(parameter: PadBufParameter) extends HWBundle(parameter):
    val i = Flipped(Bool())
    val o = Aligned(Bool())
    val pad = Aligned(Analog(parameter.width))

private class PadBufProbe(parameter: PadBufParameter) extends DVBundle[PadBufParameter, PadBufLayers](parameter)

private case class PadBufVerilogParameter() extends VerilogParameter

@generator
private object PadBuf
    extends VerilogWrapper[PadBufParameter, PadBufLayers, PadBufIO, PadBufProbe, PadBufVerilogParameter]:
    override def moduleName(parameter: PadBufParameter): String = s"PadBuf_${parameter.width}"

    override def verilogModuleName(parameter: PadBufParameter): String = s"PadBuf_${parameter.width}"

    def verilogParameter(parameter: PadBufParameter): PadBufVerilogParameter = PadBufVerilogParameter()

/** The probe's top: a pad of its own, attached to the buffer's pin. */
private case class PadTopParameter(width: Int) extends Parameter

private given upickle.default.ReadWriter[PadTopParameter] = upickle.default.macroRW

private class PadTopLayers(parameter: PadTopParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

private class PadTopIO(parameter: PadTopParameter) extends HWBundle(parameter):
    val drive = Flipped(Bool())
    val seen = Aligned(Bool())
    val pad = Aligned(Analog(parameter.width))

private class PadTopProbe(parameter: PadTopParameter) extends DVBundle[PadTopParameter, PadTopLayers](parameter)

@generator
private object PadTop extends Generator[PadTopParameter, PadTopLayers, PadTopIO, PadTopProbe]:
    override def moduleName(parameter: PadTopParameter): String = s"PadTop_${parameter.width}"

    def architecture(parameter: PadTopParameter) =
        val io = summon[Interface[PadTopIO]]
        val buf = PadBuf.instantiate(PadBufParameter(parameter.width))
        buf.io.i := io.drive
        io.seen := buf.io.o
        val padRef: Referable[Analog] = io.pad
        padRef.attach(buf.io.pad)

/** What an analog pad does when the design itself owns it: the question behind "the zaozi top should be the top".
  *
  * The DDR3 pads are `inout`, which the DSL could not express: no `Analog` constructor, and `:=` refuses analogs
  * because FIRRTL connects them only through `attach`. With both added (zaozi side), this lowers the shape a pad would
  * have — the design's own port, a buffer's analog pin, one `attach` between them — and pins what comes out: the port
  * is an `inout`, and the two ends are one net.
  */
object AnalogPadProbeTest extends TestSuite:

    private lazy val lowered: String =
        val scratch = os.temp.dir(prefix = "nzea-analog-probe")
        try
            // A stale probe module from an earlier run would count as pre-existing to `withLinked`'s working-directory
            // diffing, and the run would then look as if it had produced nothing.
            os.list(os.pwd)
                .filter(path => path.ext == "mlirbc" && path.last.startsWith("Pad"))
                .foreach(os.remove(_))
            Elaborate.withLinked(
                PadTop.moduleName(PadTopParameter(4)),
                scratch,
                cfg => PadTop.main(Array("config", cfg.toString, "--width", "4")),
                cfg => PadTop.main(Array("design", cfg.toString))
            )(Elaborate.verilog(_, TestFlow.loweringOptions))
        finally os.remove.all(scratch)

    val tests = Tests:

        test("a pad the design owns is an inout port, and attach makes the two ends one net"):
            println(s"--- lowered ---\n$lowered\n--- probe end ---")
            assert(lowered.contains("module PadTop_4("))
            assert(lowered.contains("inout"))
            assert(lowered.contains(".pad (pad)"))
