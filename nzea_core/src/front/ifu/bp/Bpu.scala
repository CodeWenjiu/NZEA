package nzea_core.front.ifu.bp

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BpuLevel, CoreConfig}
import nzea_core.back.BpUpdate
import nzea_core.isa.Isa
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BpuParameter(isa: Isa, defaultPc: Long, level: BpuLevel) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[BpuParameter] = upickle.default.macroRW

class BpuLayers(parameter: BpuParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class BpuIO(parameter: BpuParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())

  /** The address whose prediction is wanted; the answer is combinational, and the caller registers it. */
  val lookup_pc = Flipped(UInt(width))
  val pred_next_pc = Aligned(UInt(width))

  /** The resolutions to train on. */
  val update = Flipped(Valid(new BpUpdate(width)))

class BpuProbe(parameter: BpuParameter) extends DVBundle[BpuParameter, BpuLayers](parameter)

/** The fetch-side predictor: the level's tables and the composition that turns them into a next pc.
  *
  * The lookup is combinational and addressed by the pc the caller is *about to* fetch — the caller registers the
  * answer, which keeps the tables' read depth out of the pc register's own loop. The composition adds no edges of its
  * own: a redirect out of the tables comes off the buffer's target, and the answer is the looked-up address's
  * fall-through otherwise.
  *
  * The level decides what exists, inside this unit: a level that names no table builds none, and its answer is the
  * fall-through alone — exactly the sequential fetch's prediction. The unit is therefore the one place the prediction
  * is made, at every level; a caller states the level and nothing else about it.
  */
@generator
object Bpu extends Generator[BpuParameter, BpuLayers, BpuIO, BpuProbe]:
  override def moduleName(parameter: BpuParameter): String = "Bpu"

  def architecture(parameter: BpuParameter) =
    val io = summon[Interface[BpuIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width

    parameter.level match
      case BpuLevel.Bimodal(phtEntries, btbEntries) =>
        val pht = Pht.instantiate(PhtParameter(parameter.isa, parameter.defaultPc, phtEntries))
        val btb = Btb.instantiate(BtbParameter(parameter.isa, parameter.defaultPc, btbEntries))

        pht.io.clock := io.clock
        pht.io.resetN := io.resetN
        pht.io.lookup_pc := io.lookup_pc
        pht.io.update_valid := io.update.valid
        pht.io.update_pc := io.update.bits.pc
        pht.io.update_taken := io.update.bits.taken

        btb.io.clock := io.clock
        btb.io.resetN := io.resetN
        btb.io.lookup_pc := io.lookup_pc
        btb.io.update_valid := io.update.valid & io.update.bits.taken
        btb.io.update_pc := io.update.bits.pc
        btb.io.update_target := io.update.bits.target

        // The composition: a redirect needs both the direction's "taken" and a target to go to; everything else
        // continues at the fall-through of the address being predicted.
        val fallThrough = pcPlus4(io.lookup_pc, width)
        io.pred_next_pc := (pht.io.pred_taken & btb.io.pred_hit) ? (btb.io.pred_target, fallThrough)

      case BpuLevel.Off =>
        // No table exists, so the only answer is the fall-through — the sequential fetch, made by the same structure.
        io.pred_next_pc := pcPlus4(io.lookup_pc, width)
