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

case class BtbParameter(isa: Isa, defaultPc: Long, entries: Int) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)
  require(entries > 0 && (entries & (entries - 1)) == 0, s"btb entries must be a power of two, got $entries")

given upickle.default.ReadWriter[BtbParameter] = upickle.default.macroRW

class BtbLayers(parameter: BtbParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class BtbIO(parameter: BtbParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())

  /** The pc being predicted: a combinational lookup, answered in the same cycle. */
  val lookup_pc = Flipped(UInt(width))
  val pred_hit = Aligned(Bool())
  val pred_target = Aligned(UInt(width))

  /** One taken resolution to record: where the transfer left from and where it went. */
  val update_valid = Flipped(Bool())
  val update_pc = Flipped(UInt(width))
  val update_target = Flipped(UInt(width))

class BtbProbe(parameter: BtbParameter) extends DVBundle[BtbParameter, BtbLayers](parameter)

/** Target buffer: a pc-indexed slot per entry, each holding {valid, tag, target} — where a taken transfer goes.
  *
  * The buffer is written by taken resolutions only, and a lookup hits only when the slot's tag names the pc being
  * predicted. That pair is what makes a redirect from here safe: an entry only ever holds a pc that resolved taken, and
  * `index ## tag` is the entry's whole pc, so a pc whose instruction is not a taken branch cannot hit — a non-branch
  * fetch is never steered anywhere. Aliasing between branches costs a displacement, never a false hit.
  *
  * Only the target's word address is stored (the alignment bits are zero and rewritten on read), which is the widest
  * field in the entry and the reason a target buffer is the expensive structure beside the direction table.
  */
@generator
object Btb extends Generator[BtbParameter, BtbLayers, BtbIO, BtbProbe]:
  override def moduleName(parameter: BtbParameter): String = "Btb"

  def architecture(parameter: BtbParameter) =
    val io = summon[Interface[BtbIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val entries = parameter.entries
    val indexBits = log2Ceil(entries)
    val tagBits = width - indexBits - 2

    def indexOf(pc: Referable[UInt]): Node[UInt] = Node(pc.asBits).bits(indexBits + 1, 2).asUInt
    def tagOf(pc: Referable[UInt]): Node[UInt] = Node(pc.asBits).bits(width - 1, indexBits + 2).asUInt

    val valids = Reg(Vec(entries, Bool()))
    val tags = Reg(Vec(entries, UInt(tagBits)))
    val targets = Reg(Vec(entries, UInt(width - 2)))
    when(!io.resetN.asBool) {
      for index <- 0 until entries do
        valids(index) := false.B
        tags(index) := 0.U(tagBits)
        targets(index) := 0.U(width - 2)
    }

    val slot = indexOf(io.lookup_pc)
    io.pred_hit := valids.ref(slot) & (tags.ref(slot) === tagOf(io.lookup_pc))
    io.pred_target := (targets.ref(slot).asBits ## 0.B(2)).asUInt

    // The write: a flop array has no indexed write port — one enable per entry is the write port — so the slot is
    // selected by a compare per entry, the way the register file writes. The written values are named outside the
    // guards: a value's definition belongs to the block that creates it, and only the connects sit inside the `when`.
    val write = indexOf(io.update_pc)
    val updateTag = tagOf(io.update_pc)
    val updateTarget: Node[UInt] = Node(io.update_target.asBits).bits(width - 1, 2).asUInt
    when(io.update_valid) {
      for index <- 0 until entries do
        when(write === index.U(indexBits)) {
          valids(index) := true.B
          tags(index) := updateTag
          targets(index) := updateTarget
        }
    }
