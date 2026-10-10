package nzea_core.front.ifu.bp

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
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

    /** The pc being predicted: the read is issued with it, and the answer is out a cycle later. */
    val lookup_pc = Flipped(UInt(width))
    val pred_hit = Aligned(Bool())
    val pred_target = Aligned(UInt(width))

    /** One taken resolution to record: where the transfer left from and where it went. */
    val update_valid = Flipped(Bool())
    val update_pc = Flipped(UInt(width))
    val update_target = Flipped(UInt(width))

class BtbProbe(parameter: BtbParameter) extends DVBundle[BtbParameter, BtbLayers](parameter)

/** Target buffer: a pc-indexed slot per entry, each holding `{tag, target}` — where a taken transfer goes.
  *
  * The buffer is written by taken resolutions only, and a lookup hits only when the slot's tag names the pc being
  * predicted. That pair is what makes a redirect from here safe: an entry only ever holds a pc that resolved taken, and
  * `index ## tag` is the entry's whole pc, so a pc whose instruction is not a taken branch cannot hit — a non-branch
  * fetch is never steered anywhere. Aliasing between branches costs a displacement, never a false hit.
  *
  * `{tag, target}` lives in a [[nzea_rtl.stdlib.SyncRam]] (synchronous read, write-only updates); the entry's *valid*
  * bit does not — it stays in flops, because it is state that must start defined: it resets to zero, and a fresh buffer
  * misses without consulting the read data at all. The tag compare and the valid read are addressed by the pc the
  * outstanding read was issued with, which is also what a hit is reported for.
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
        val targetBits = width - 2

        def indexOf(pc: Referable[UInt]): Node[UInt] = Node(pc.asBits).bits(indexBits + 1, 2).asUInt
        def tagOf(pc: Referable[UInt]): Node[UInt] = Node(pc.asBits).bits(width - 1, indexBits + 2).asUInt

        // The pc the outstanding read was issued with. It resets to the reset pc so the first cycle's valid read — and with
        // it the first hit — is answered from the reset valids rather than from an undefined index.
        val readPc = RegInit((BigInt(parameter.config.defaultPc) & ((BigInt(1) << width) - 1)).U(width))

        val valids = Reg(Vec(entries, Bool()))
        when(!io.resetN.asBool) {
            for index <- 0 until entries do valids(index) := false.B
        }

        val table = SyncRam.instantiate(
            SyncRamParameter(tagBits + targetBits, entries, SyncRamPorts.OneR1W, SyncRamReadUnderWrite.ReadFirst)
        )
        table.io.clock := io.clock
        table.io.rEn := true.B
        table.io.rAddr := indexOf(io.lookup_pc)

        // The write: a taken resolution writes its own tag and target whole; the valid bit it sets is the flop beside the
        // memory. The written values are named outside the guards: a value's definition belongs to the block that creates
        // it, and only the connects sit inside the `when`.
        val write = indexOf(io.update_pc)
        val updateTag: Node[UInt] = tagOf(io.update_pc)
        val updateTarget: Node[UInt] = Node(io.update_target.asBits).bits(width - 1, 2).asUInt
        table.io.wEn := io.update_valid
        table.io.wAddr := write
        table.io.wData := (updateTag.asBits ## updateTarget.asBits).asUInt
        when(io.update_valid) {
            for index <- 0 until entries do when(write === index.U(indexBits)) { valids(index) := true.B }
        }

        // The answer, for the pc the read was issued with: the stored tag against that pc's, gated by its valid bit, and
        // the stored target with its alignment bits rewritten.
        readPc := io.lookup_pc
        val entry: Node[Bits] = Node(table.io.rData.asBits)
        val storedTag: Node[UInt] = entry.bits(tagBits + targetBits - 1, targetBits).asUInt
        val storedTarget: Node[UInt] = (entry.bits(targetBits - 1, 0) ## 0.B(2)).asUInt

        /** The stored tag against the outstanding read's, in four-bit groups under a kept tree.
          *
          * The compare is the fetch loop's longest single cone — the loop is one cycle by construction, so its depth is
          * the whole budget — and a mapper left to itself reads a wide equality as one function and folds it into a weak
          * two-input chain. The groups are the equality's own depth written down, and `dontTouch` is what keeps the
          * mapping from re-reading them whole: one group's answer is one comparison, and the tree above them is kept
          * level by level, the same treatment the prefix primitives get for the same reason.
          */
        def tagMatch(stored: Node[UInt], lookup: Node[UInt]): Node[Bool] =
            val storedBits: Node[Bits] = Node(stored.asBits)
            val lookupBits: Node[Bits] = Node(lookup.asBits)
            def group(lo: Int): Node[Bool] =
                val hi = math.min(lo + 3, tagBits - 1)
                dontTouch(storedBits.bits(hi, lo).asUInt === lookupBits.bits(hi, lo).asUInt)
            def tree(parts: Seq[Node[Bool]]): Node[Bool] =
                if parts.sizeIs == 1 then parts.head
                else
                    tree(
                        parts
                            .grouped(2)
                            .map(pair => dontTouch(if pair.sizeIs == 1 then pair.head else pair(0) & pair(1)))
                            .toSeq
                    )
            tree((0 until (tagBits + 3) / 4).map(i => group(i * 4)).toSeq)

        io.pred_hit := valids.ref(indexOf(readPc)) & tagMatch(storedTag, tagOf(readPc))
        io.pred_target := storedTarget
