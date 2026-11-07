package nzea_lab.generators

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The black-box parameters carry nothing: they exist so each wrapper's `verilogParameter` has a case class. */
case class AluBlackBoxParameter() extends VerilogParameter
case class RegFileBlackBoxParameter() extends VerilogParameter
case class ShifterBlackBoxParameter() extends VerilogParameter

/** The course's `alu`, instantiated by `simple_cpu` as an external module: its body is the separately generated
  * `alu.v`, so the CPU's dump must not carry a second definition.
  */
@generator
object AluBlackBox extends VerilogWrapper[AluParameter, AluLayers, AluIO, AluProbe, AluBlackBoxParameter]:

    override def verilogModuleName(parameter: AluParameter): String = "alu"

    override def moduleName(parameter: AluParameter): String = "alu"

    def verilogParameter(parameter: AluParameter): AluBlackBoxParameter = AluBlackBoxParameter()

/** The course's `reg_file`, instantiated by `simple_cpu` as an external module. */
@generator
object RegFileBlackBox
    extends VerilogWrapper[RegFileParameter, RegFileLayers, RegFileIO, RegFileProbe, RegFileBlackBoxParameter]:

    override def verilogModuleName(parameter: RegFileParameter): String = "reg_file"

    override def moduleName(parameter: RegFileParameter): String = "reg_file"

    def verilogParameter(parameter: RegFileParameter): RegFileBlackBoxParameter = RegFileBlackBoxParameter()

/** The course's `shifter`, instantiated by `simple_cpu` as an external module. */
@generator
object ShifterBlackBox
    extends VerilogWrapper[ShifterParameter, ShifterLayers, ShifterIO, ShifterProbe, ShifterBlackBoxParameter]:

    override def verilogModuleName(parameter: ShifterParameter): String = "shifter"

    override def moduleName(parameter: ShifterParameter): String = "shifter"

    def verilogParameter(parameter: ShifterParameter): ShifterBlackBoxParameter = ShifterBlackBoxParameter()
