package nzea_core.stats

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.StatsRegs
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One counter's ports of a [[StatsRegs]] black box: raise `en` with the counter's next value, read the current one on
  * `value`. The field name of the containing bundle is the counter's VPI name, because that is what the box's ports are
  * called (`stat_cycle` flattens to `stat_cycle_en` and friends).
  */
class StatsPort extends Bundle:
  val en = Flipped(Bool())
  val data = Flipped(UInt(StatsRegs.Width))
  val value = Aligned(UInt(StatsRegs.Width))

/** The retire-throughput counters, as the middle counts them: one cycle per clock, one commit per report. */
case class ThroughputCountersParameter() extends Parameter

given upickle.default.ReadWriter[ThroughputCountersParameter] = upickle.default.macroRW

class ThroughputCountersLayers(parameter: ThroughputCountersParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ThroughputCountersIO(parameter: ThroughputCountersParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Bool())
  val stat_cycle = Aligned(new StatsPort)
  val stat_inst_commit = Aligned(new StatsPort)

class ThroughputCountersProbe(parameter: ThroughputCountersParameter)
    extends DVBundle[ThroughputCountersParameter, ThroughputCountersLayers](parameter)

case class ThroughputCountersVerilogParameter() extends VerilogParameter

@generator
object ThroughputCounters
    extends VerilogWrapper[
      ThroughputCountersParameter,
      ThroughputCountersLayers,
      ThroughputCountersIO,
      ThroughputCountersProbe,
      ThroughputCountersVerilogParameter
    ]:
  override def moduleName(parameter: ThroughputCountersParameter): String = "ThroughputCounters"

  def verilogModuleName(parameter: ThroughputCountersParameter): String = "ThroughputCounters"

  def verilogParameter(parameter: ThroughputCountersParameter): ThroughputCountersVerilogParameter =
    ThroughputCountersVerilogParameter()

/** The branch counters, as the int path sees them: every branch or jump that resolves, and how it was predicted. */
case class BpCountersParameter() extends Parameter

given upickle.default.ReadWriter[BpCountersParameter] = upickle.default.macroRW

class BpCountersLayers(parameter: BpCountersParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class BpCountersIO(parameter: BpCountersParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Bool())
  val stat_bp_branch = Aligned(new StatsPort)
  val stat_bp_mispred = Aligned(new StatsPort)
  val stat_bp_actual_taken = Aligned(new StatsPort)

class BpCountersProbe(parameter: BpCountersParameter) extends DVBundle[BpCountersParameter, BpCountersLayers](parameter)

case class BpCountersVerilogParameter() extends VerilogParameter

@generator
object BpCounters
    extends VerilogWrapper[
      BpCountersParameter,
      BpCountersLayers,
      BpCountersIO,
      BpCountersProbe,
      BpCountersVerilogParameter
    ]:
  override def moduleName(parameter: BpCountersParameter): String = "BpCounters"

  def verilogModuleName(parameter: BpCountersParameter): String = "BpCounters"

  def verilogParameter(parameter: BpCountersParameter): BpCountersVerilogParameter = BpCountersVerilogParameter()
