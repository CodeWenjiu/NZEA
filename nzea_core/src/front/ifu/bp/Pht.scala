package nzea_core.front.ifu.bp

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.isa.Isa
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class PhtParameter(isa: Isa, defaultPc: Long, entries: Int) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)
  require(entries > 0 && (entries & (entries - 1)) == 0, s"pht entries must be a power of two, got $entries")

given upickle.default.ReadWriter[PhtParameter] = upickle.default.macroRW

class PhtLayers(parameter: PhtParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class PhtIO(parameter: PhtParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())

  /** The pc being predicted: a combinational lookup, answered in the same cycle. */
  val lookup_pc = Flipped(UInt(width))
  val pred_taken = Aligned(Bool())

  /** One resolution to train on: the branch's pc and its outcome. */
  val update_valid = Flipped(Bool())
  val update_pc = Flipped(UInt(width))
  val update_taken = Flipped(Bool())

class PhtProbe(parameter: PhtParameter) extends DVBundle[PhtParameter, PhtLayers](parameter)

/** Direction table: one 2-bit saturating counter per pc-indexed slot, the counter's high bit the prediction.
  *
  * The table watches every resolved branch and learns its bias; a counter saturates toward taken or not-taken and only
  * the sign (the high bit) is read, so one unexpected outcome cannot flip a prediction the branch had been consistently
  * making. A no-history table on purpose: it is the base the tagged levels grow above, and it already carries the cases
  * that need no history at all — loops and strongly biased branches.
  *
  * Reads are combinational (the fetch needs the answer inside the lookup cycle, which the caller registers); an update
  * lands at the end of the resolution cycle. A read and an update of the same slot in one cycle are both fine and only
  * cost the read one resolution of staleness, which the prediction's own tolerance covers.
  */
@generator
object Pht extends Generator[PhtParameter, PhtLayers, PhtIO, PhtProbe]:
  override def moduleName(parameter: PhtParameter): String = "Pht"

  def architecture(parameter: PhtParameter) =
    val io = summon[Interface[PhtIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val entries = parameter.entries
    val indexBits = log2Ceil(entries)

    /** A pc's slot: the word-aligned pc's low bits, the same slice the legacy table used. */
    def indexOf(pc: Referable[UInt]): Node[UInt] = Node(pc.asBits).bits(indexBits + 1, 2).asUInt

    // Every slot starts defined — a counter at zero, which reads as not-taken — so the four-state simulation sees the
    // same reset state as the tables' empty state implies.
    val counters = Reg(Vec(entries, UInt(2)))
    when(!io.resetN.asBool) {
      for index <- 0 until entries do counters(index) := 0.U(2)
    }

    io.pred_taken := counters.ref(indexOf(io.lookup_pc)).asBits.bit(1)

    // The update: the counter is read back and written saturated. A flop array has no indexed write port — one enable
    // per slot is the write port — so the slot is selected by a compare per entry, the way the register file writes.
    val writeIndex = indexOf(io.update_pc)
    val slot = counters.ref(writeIndex)
    val bumped = (slot + 1.U(2)).asBits.bits(1, 0).asUInt
    val dropped = (slot - 1.U(2)).asBits.bits(1, 0).asUInt
    val onTaken: Referable[UInt] = (slot === 3.U(2)) ? (3.U(2), bumped)
    val onNotTaken: Referable[UInt] = (slot === 0.U(2)) ? (0.U(2), dropped)
    val saturating: Referable[UInt] = io.update_taken ? (onTaken, onNotTaken)
    when(io.update_valid) {
      for index <- 0 until entries do when(writeIndex === index.U(indexBits)) { counters(index) := saturating }
    }
