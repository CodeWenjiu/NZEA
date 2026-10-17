package nzea_fpga.primitives

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.{DumpRegistry, FileList}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BufgParameter() extends Parameter

given upickle.default.ReadWriter[BufgParameter] = upickle.default.macroRW

class BufgLayers(parameter: BufgParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BufgIO(parameter: BufgParameter) extends HWBundle(parameter):
    val I = Flipped(Clock())
    val O = Aligned(Clock())

class BufgProbe(parameter: BufgParameter) extends DVBundle[BufgParameter, BufgLayers](parameter)

case class BufgVerilogParameter() extends VerilogParameter

/** The wrapper the RTL names: one module whose body is the primitive, under the name synthesis knows it by. */
@generator
object BufgModule extends VerilogWrapper[BufgParameter, BufgLayers, BufgIO, BufgProbe, BufgVerilogParameter]:

    override def moduleName(parameter: BufgParameter): String = "Bufg"

    override def verilogModuleName(parameter: BufgParameter): String = "Bufg"

    def verilogParameter(parameter: BufgParameter): BufgVerilogParameter = BufgVerilogParameter()

/** The device's global clock buffer, as a module the dump carries beside the RTL.
  *
  * A clock that reaches the fabric from a wire is a wire: the router treats it as one and inserts the buffer it thinks
  * it needs, where it decides to — this states that the clock *is* global. It also keeps that buffer off the paths that
  * must not have one: a clock generator's input wants the IO buffer's own path (its compensation is about that delay),
  * and a PLL fed through the tool's own buffer fails the IO-clock DRC (verified: `REQP-1712`).
  */
object Bufg:

    private var used = false

    DumpRegistry.onReset(() => used = false)

    def verilog(parameter: BufgParameter): String =
        s"""module ${BufgModule.moduleName(parameter)}(
           |  input I,
           |  output O
           |);
           |  BUFG bufg (.I(I), .O(O));
           |endmodule
           |""".stripMargin

    def emitModules(dir: os.Path): Unit =
        if used then
            os.makeDir.all(dir)
            val name = s"${BufgModule.moduleName(BufgParameter())}.sv"
            os.write.over(dir / name, verilog(BufgParameter()))
            FileList.append(dir, Seq(name))

    def instantiate(parameter: BufgParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[BufgIO, BufgProbe] =
        used = true
        DumpRegistry.onEmit("bufg")(dir => Bufg.emitModules(dir))
        BufgModule.instantiate(parameter)
