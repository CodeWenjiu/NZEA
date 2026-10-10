package nzea_core.front.ifu.bp

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuConfig, BpuStrategy, CoreConfig}
import nzea_core.back.BpUpdate
import nzea_core.isa.Isa
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BpuParameter(isa: Isa, defaultPc: Long, bpu: Option[BpuConfig]) extends Parameter:
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

    /** The fall-through of the address being predicted: the pc register's own `pc + 4`, made by the caller — the same
      * value the request it packs carries — and the composition's default answer.
      */
    val fall_through = Flipped(UInt(width))
    val pred_next_pc = Aligned(UInt(width))

    /** The direction counter the prediction read, for the fetch to carry back as the branch's training evidence. */
    val pred_count = Aligned(UInt(BpUpdate.phtCountBits))

    /** The resolutions to train on: the port follows the config — with no predictor there is no table to train, so it
      * does not exist.
      */
    val update: Option[BundleField[ValidIO[BpUpdate]]] =
        Option.when(parameter.bpu.isDefined)(Flipped(Valid(new BpUpdate(width))))

class BpuProbe(parameter: BpuParameter) extends DVBundle[BpuParameter, BpuLayers](parameter)

/** The fetch-side predictor: the configured strategy's tables and the composition that turns them into a next pc.
  *
  * The lookup is a read issued a cycle before its answer is used: the caller addresses the predictor with the pc
  * register's *next* value — the call that updates the register — and the answer is composed out of the tables' own
  * output registers, so next cycle, when that value is the pc, the answer is the prediction for it. The fall-through
  * half of the composition is the caller's `pc + 4` of the address being predicted — taken live off the pc register, so
  * the +4 races the cycle's redirect and handshake controls instead of sitting after the select that chooses the pc's
  * next value. A redirect needs both the direction's "taken" and a target to go to; everything else continues at the
  * fall-through.
  *
  * The config decides what exists, inside this unit: `None` builds no predictor, and its answer is the fall-through
  * alone — exactly the sequential fetch's prediction, registered the same way. The unit is therefore the one place the
  * prediction is made, at every configuration; a caller states the config and nothing else about it.
  *
  * The tables hold no reset: a memory entry has no reset value, and what a fresh table predicts is the empty table —
  * the storages start as the simulator initializes them (zero, in the two-state model this design is verified in), and
  * a never-written slot reads as not-taken with no buffer hit. The composition itself holds no state — the fall-through
  * is the pc register's live `pc + 4` — so the very first fetch is predicted the sequential way by construction.
  */
@generator
object Bpu extends Generator[BpuParameter, BpuLayers, BpuIO, BpuProbe]:
    override def moduleName(parameter: BpuParameter): String = "Bpu"

    def architecture(parameter: BpuParameter) =
        val io = summon[Interface[BpuIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width

        // The answer's fall-through is the caller's — the pc register's own `pc + 4`. It is used live, off a register, so
        // the +4 hangs beside the pc register and races the cycle's redirect and handshake controls instead of following the
        // select that chooses the pc's next value. The registered copy it replaces held exactly this value every cycle: it
        // was written from the read's own address, which is the pc register's next value — the pc register itself, one cycle
        // later.

        parameter.bpu match
            case Some(config) =>
                // The return-address stack is in the config language but not in the hardware yet: reject it loudly
                // rather than build a predictor that silently ignores it.
                require(config.ras.isEmpty, "the return-address stack is not built yet; leave ras empty")

                val update = io.update.get

                config.strategy match
                    case BpuStrategy.Bimodal(phtEntries, btbEntries) =>
                        val pht = Pht.instantiate(PhtParameter(parameter.isa, parameter.defaultPc, phtEntries))
                        val btb = Btb.instantiate(BtbParameter(parameter.isa, parameter.defaultPc, btbEntries))

                        pht.io.clock := io.clock
                        pht.io.resetN := io.resetN
                        pht.io.lookup_pc := io.lookup_pc
                        pht.io.update_valid := update.valid
                        pht.io.update_pc := update.bits.pc
                        pht.io.update_taken := update.bits.taken
                        pht.io.update_count := update.bits.pht_count

                        btb.io.clock := io.clock
                        btb.io.resetN := io.resetN
                        btb.io.lookup_pc := io.lookup_pc
                        btb.io.update_valid := update.valid & update.bits.taken
                        btb.io.update_pc := update.bits.pc
                        btb.io.update_target := update.bits.target

                        // The composition: a redirect needs both the direction's "taken" and a target to go to;
                        // everything else continues at the fall-through of the address being predicted.
                        io.pred_next_pc := (pht.io.pred_taken & btb.io.pred_hit) ? (btb.io.pred_target, io.fall_through)
                        io.pred_count := pht.io.count

            case None =>
                // No predictor exists, so the only answer is the fall-through — the sequential fetch, made by the same
                // structure.
                io.pred_next_pc := io.fall_through
                io.pred_count := 0.U(BpUpdate.phtCountBits)
