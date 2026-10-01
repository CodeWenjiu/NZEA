package nzea_core.front.ifu.bp

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.back.BpUpdate
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

    /** The pc being predicted: the read is issued with it, and the answer is out a cycle later. */
    val lookup_pc = Flipped(UInt(width))
    val pred_taken = Aligned(Bool())
    val count = Aligned(UInt(BpUpdate.phtCountBits))

    /** One resolution to train on: the branch's pc, its outcome, and the counter its fetch read. */
    val update_valid = Flipped(Bool())
    val update_pc = Flipped(UInt(width))
    val update_taken = Flipped(Bool())
    val update_count = Flipped(UInt(BpUpdate.phtCountBits))

class PhtProbe(parameter: PhtParameter) extends DVBundle[PhtParameter, PhtLayers](parameter)

/** Direction table: one 2-bit saturating counter per pc-indexed slot, the counter's high bit the prediction.
  *
  * The table watches every resolved branch and learns its bias; a counter saturates toward taken or not-taken and only
  * the sign (the high bit) is read, so one unexpected outcome cannot flip a prediction the branch had been consistently
  * making. A no-history table on purpose: it is the base the tagged levels grow above, and it already carries the cases
  * that need no history at all — loops and strongly biased branches.
  *
  * The storage is a [[nzea_rtl.stdlib.SyncRam]]: the read is synchronous (issued a cycle before its answer is used) and
  * the update is a plain write — the counter's new value is computed from the counter the branch's own fetch read
  * ([[BpUpdate.pht_count]]), not from a read back through a port the prediction is using. What that trades away is
  * freshness under aliasing: two aliasing branches in flight together each write the value their own fetch read, so one
  * of the two increments is lost — a slower learn on conflicting slots, never a wrong prediction. The read and the
  * write in the same cycle are the classic read-first pair: the read gets the entry as it was.
  *
  * Nothing here is reset — a memory entry has no reset value. A fresh table therefore predicts what its storage starts
  * as, which in the two-state simulation this design is verified in is zero: not-taken, exactly the empty table.
  */
@generator
object Pht extends Generator[PhtParameter, PhtLayers, PhtIO, PhtProbe]:
    override def moduleName(parameter: PhtParameter): String = "Pht"

    def architecture(parameter: PhtParameter) =
        val io = summon[Interface[PhtIO]]
        given ClockScope = ClockScope.posedge(io.clock)

        val entries = parameter.entries
        val indexBits = log2Ceil(entries)

        /** A pc's slot: the word-aligned pc's low bits, the same slice the legacy table used. */
        def indexOf(pc: Referable[UInt]): Node[UInt] = Node(pc.asBits).bits(indexBits + 1, 2).asUInt

        /** The counter's next value: one step toward the outcome, saturated at either end. */
        def saturating(count: Referable[UInt], taken: Referable[Bool]): Referable[UInt] =
            val bumped = (count + 1.U(BpUpdate.phtCountBits)).asBits.bits(BpUpdate.phtCountBits - 1, 0).asUInt
            val dropped = (count - 1.U(BpUpdate.phtCountBits)).asBits.bits(BpUpdate.phtCountBits - 1, 0).asUInt
            val max = ((1 << BpUpdate.phtCountBits) - 1).U(BpUpdate.phtCountBits)
            val onTaken: Referable[UInt] = (count === max) ? (max, bumped)
            val onNotTaken: Referable[UInt] =
                (count === 0.U(BpUpdate.phtCountBits)) ? (0.U(BpUpdate.phtCountBits), dropped)
            taken ? (onTaken, onNotTaken)

        val table = SyncRam.instantiate(
            SyncRamParameter(BpUpdate.phtCountBits, entries, SyncRamPorts.OneR1W, SyncRamReadUnderWrite.ReadFirst)
        )
        table.io.clock := io.clock
        table.io.rEn := true.B
        table.io.rAddr := indexOf(io.lookup_pc)
        io.pred_taken := Node(table.io.rData.asBits).bit(1)
        io.count := table.io.rData
        table.io.wEn := io.update_valid
        table.io.wAddr := indexOf(io.update_pc)
        table.io.wData := saturating(io.update_count, io.update_taken)
