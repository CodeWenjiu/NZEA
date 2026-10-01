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

  /** The address whose prediction is wanted: the read is issued with it, and the answer — [[pred_next_pc]] and the
    * training evidence beside it — is out a cycle later.
    */
  val lookup_pc = Flipped(UInt(width))
  val pred_next_pc = Aligned(UInt(width))

  /** The direction counter the prediction read, for the fetch to carry back as the branch's training evidence. */
  val pred_count = Aligned(UInt(BpUpdate.phtCountBits))

  /** The resolutions to train on. */
  val update = Flipped(Valid(new BpUpdate(width)))

class BpuProbe(parameter: BpuParameter) extends DVBundle[BpuParameter, BpuLayers](parameter)

/** The fetch-side predictor: the level's tables and the composition that turns them into a next pc.
  *
  * The lookup is a read issued a cycle before its answer is used: the caller addresses the predictor with the pc
  * register's *next* value — the call that updates the register — and the answer is composed out of the tables' own
  * output registers, so next cycle, when that value is the pc, the answer is the prediction for it. The fall-through
  * half of the composition is the registered fall-through of the address the read was issued with, so no adder sits in
  * the answer's path. A redirect needs both the direction's "taken" and a target to go to; everything else continues at
  * the fall-through.
  *
  * The level decides what exists, inside this unit: a level that names no table builds none, and its answer is the
  * fall-through alone — exactly the sequential fetch's prediction, registered the same way. The unit is therefore the
  * one place the prediction is made, at every level; a caller states the level and nothing else about it.
  *
  * The tables hold no reset: a memory entry has no reset value, and what a fresh table predicts is the empty table —
  * the storages start as the simulator initializes them (zero, in the two-state model this design is verified in), and
  * a never-written slot reads as not-taken with no buffer hit. The reset here only seeds the fall-through register, so
  * the very first fetch is predicted the sequential way.
  */
@generator
object Bpu extends Generator[BpuParameter, BpuLayers, BpuIO, BpuProbe]:
  override def moduleName(parameter: BpuParameter): String = "Bpu"

  def architecture(parameter: BpuParameter) =
    val io = summon[Interface[BpuIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    // The platform's reset pc, masked to the register width: a BigInt shift, because `1L << 64` wraps around in a Long.
    val mask = (BigInt(1) << width) - 1
    val pcReset = BigInt(parameter.config.defaultPc) & mask

    // The answer's fall-through, registered with the read it belongs to: it resets to the reset pc's own fall-through,
    // so the first fetch is predicted the sequential way, until the tables have seen a resolution.
    val fallThrough = RegInit(((pcReset + 4) & mask).U(width))
    fallThrough := pcPlus4(io.lookup_pc, width)

    parameter.level match
      case BpuLevel.Bimodal(phtEntries, btbEntries) =>
        val pht = Pht.instantiate(PhtParameter(parameter.isa, parameter.defaultPc, phtEntries))
        val btb = Btb.instantiate(BtbParameter(parameter.isa, parameter.defaultPc, btbEntries))

        pht.io.clock := io.clock
        pht.io.lookup_pc := io.lookup_pc
        pht.io.update_valid := io.update.valid
        pht.io.update_pc := io.update.bits.pc
        pht.io.update_taken := io.update.bits.taken
        pht.io.update_count := io.update.bits.pht_count

        btb.io.clock := io.clock
        btb.io.resetN := io.resetN
        btb.io.lookup_pc := io.lookup_pc
        btb.io.update_valid := io.update.valid & io.update.bits.taken
        btb.io.update_pc := io.update.bits.pc
        btb.io.update_target := io.update.bits.target

        // The composition: a redirect needs both the direction's "taken" and a target to go to; everything else
        // continues at the fall-through of the address being predicted.
        io.pred_next_pc := (pht.io.pred_taken & btb.io.pred_hit) ? (btb.io.pred_target, fallThrough)
        io.pred_count := pht.io.count

      case BpuLevel.Off =>
        // No table exists, so the only answer is the fall-through — the sequential fetch, made by the same structure.
        io.pred_next_pc := fallThrough
        io.pred_count := 0.U(BpUpdate.phtCountBits)
