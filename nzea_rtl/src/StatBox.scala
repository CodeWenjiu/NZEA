package nzea_rtl

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One counter's black box: a register named after the counter, and the ports a module drives it through.
  *
  * The state stays in hand-written SystemVerilog because that is where the `verilator public_flat_rd` metacomment can
  * sit — a simulator reads every `stat_*` register by leaf name — and because a black box is opaque to constant
  * folding. One box per counter is what lets a module declare a counter where the event is, without knowing its
  * neighbours: the box is the same shape every time, and only the register's name and width come from the declaration.
  */
object StatBox:

  /** Width of every box's ports: the schema's maximum, so one port bundle serves counters of any declared width. */
  val Width: Int = 64

  /** SystemVerilog body of one counter's box. A pure function, so tests can pin the text.
    *
    * The register carries the counter's own name because that is the leaf name a simulator finds it under; the module
    * wraps exactly it, so [[StatRegistry.boxModuleName]] names the module after the counter too.
    */
  def verilog(moduleName: String, counter: StatCounter): String =
    s"""module $moduleName(
       |  input clock,
       |  input resetN,
       |  input en,
       |  input [${Width - 1}:0] data,
       |  output [${Width - 1}:0] value
       |);
       |  reg [${counter.width - 1}:0] ${counter.name} /*verilator public_flat_rd*/;
       |  assign value = ${counter.name};
       |  always @(posedge clock) begin
       |    if (!resetN) ${counter.name} <= '0;
       |    else if (en) ${counter.name} <= data;
       |  end
       |endmodule
       |""".stripMargin

/** What a box holds: the counter's name and width. The box module is named after the counter, so this parameter is also
  * what names the emitted file and the instantiation.
  */
case class StatBoxParameter(name: String, width: Int) extends Parameter

given upickle.default.ReadWriter[StatBoxParameter] = upickle.default.macroRW

class StatBoxLayers(parameter: StatBoxParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class StatBoxIO(parameter: StatBoxParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Bool())
  val en = Flipped(Bool())
  val data = Flipped(UInt(StatBox.Width))
  val value = Aligned(UInt(StatBox.Width))

class StatBoxProbe(parameter: StatBoxParameter) extends DVBundle[StatBoxParameter, StatBoxLayers](parameter)

case class StatBoxVerilogParameter() extends VerilogParameter

@generator
object StatBoxModule
    extends VerilogWrapper[StatBoxParameter, StatBoxLayers, StatBoxIO, StatBoxProbe, StatBoxVerilogParameter]:
  /** The module is the counter's own box, so it is named after the counter: unique by construction (counter names are),
    * and what the emitted file is called.
    */
  override def verilogModuleName(parameter: StatBoxParameter): String = parameter.name

  override def moduleName(parameter: StatBoxParameter): String = parameter.name

  /** No Verilog parameters: the register name is what a counter varies in, and a name cannot be a parameter.
    */
  def verilogParameter(parameter: StatBoxParameter): StatBoxVerilogParameter = StatBoxVerilogParameter()
