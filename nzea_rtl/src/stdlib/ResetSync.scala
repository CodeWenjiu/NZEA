package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class ResetSyncParameter() extends Parameter

given upickle.default.ReadWriter[ResetSyncParameter] = upickle.default.macroRW

class ResetSyncLayers(parameter: ResetSyncParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The synchronizer's ports: the clock it runs on, the raw asynchronous reset (active-low, as a board's button is), and
  * the reset it hands on.
  */
class ResetSyncIO(parameter: ResetSyncParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val rawN = Flipped(Reset())
    val syncN = Aligned(Reset())

class ResetSyncProbe(parameter: ResetSyncParameter) extends DVBundle[ResetSyncParameter, ResetSyncLayers](parameter)

/** An asynchronous reset, asserted asynchronously and released synchronously: the two flops sample the raw level, and
  * the raw level also holds them cleared while it is low — so a release is de-metastabilized, and a press takes effect
  * at once. The flops' init is the asserted state, which is where a board's power-up lands anyway.
  */
@generator
object ResetSync extends Generator[ResetSyncParameter, ResetSyncLayers, ResetSyncIO, ResetSyncProbe]:
    override def moduleName(parameter: ResetSyncParameter): String = "ResetSync"

    def architecture(parameter: ResetSyncParameter) =
        val io = summon[Interface[ResetSyncIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.asyncActiveLow(io.rawN)

        val stage0 = RegInit(false.B)
        stage0 := io.rawN.asBool
        val stage1 = RegInit(false.B)
        stage1 := stage0
        io.syncN := stage1.asReset
