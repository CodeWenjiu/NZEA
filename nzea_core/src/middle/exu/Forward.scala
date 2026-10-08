package nzea_core.middle.exu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.{IssueSlot, IssueUop, PathResult}
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.isa.{Gpr, Isa}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class ForwardParameter(isa: Isa, defaultPc: Long, bypass: BypassLevel, lsqDepth: Int) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The queue's entries as this module reads them; a single dummy slot when no queue is built (`lsqDepth` zero), so
      * the column has a shape either way and the synthesis folds the dummy away.
      */
    val entryCount: Int = ForwardParameter.entryCount(lsqDepth)

    /** The column's length: the slot's writer and the entries. The retiring write is not in it — it is the middle's. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[ForwardParameter] = upickle.default.macroRW

object ForwardParameter:

    /** The entries a configuration's column carries: the queue's depth, or one dummy slot when none is built. */
    def entryCount(lsqDepth: Int): Int = if lsqDepth == 0 then 1 else lsqDepth

    /** The offers a configuration's column carries: the slot's writer and the entries. */
    def offerCount(lsqDepth: Int): Int = entryCount(lsqDepth) + 1

class ForwardLayers(parameter: ForwardParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ForwardIO(parameter: ForwardParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The completing instruction: valid the cycle the slot's uop is finished, carrying the value it produced. */
    val exec = Flipped(ValidIO(new PathResult(width)))

    /** The execute slot's uop: the youngest writer in flight, named by its destination. */
    val issue = Flipped(new IssueSlot(new IssueUop(parameter.config)))

    /** The queue as this network reads it: the writers between the completing instruction and the retiring write, in
      * program order — the head is the oldest, the position `(index - head) mod depth` the age.
      */
    val view = Flipped(new QueueView(width, parameter.entryCount))

    /** The column, out to the middle: the in-flight writers, youngest first ([[nzea_rtl.stdlib.ForwardOffer]]). */
    val offers = Aligned(Vec(parameter.offerCount, new ForwardOffer(width, Gpr.indexWidth)))

    /** The level's verdict for the retiring write the middle holds: whether the level forwards it. */
    val commit_armed = Aligned(Bool())

class ForwardProbe(parameter: ForwardParameter) extends DVBundle[ForwardParameter, ForwardLayers](parameter)

/** The forward network: the in-flight writers the execution face holds gathered into one column of offers, youngest
  * first, each cut to the level's reach.
  *
  * The producers do not know who reads them and the consumers do not know who produces: the column is the whole
  * contract, and a consumer resolves its own read against it. The order is program order — the slot's uop is the
  * youngest writer in flight, the queue's entries read back from the tail behind it — so a match needs no tag: the
  * first offer that answers a register is its youngest writer. The middle's retiring write is older than everything
  * here and lives in the middle, so it is not in the column: only the level's verdict for it crosses.
  *
  * The level is the column's `armed` bit: a writer the level cannot forward — the completing value and the entries
  * below `Execute` — is broadcast unarmed, which is what keeps a read that names it waiting. The wait a read owes is
  * the same column read the other way, so no consumer has to know the level.
  */
@generator
object Forward extends Generator[ForwardParameter, ForwardLayers, ForwardIO, ForwardProbe]:
    override def moduleName(parameter: ForwardParameter): String = "Forward"

    def architecture(parameter: ForwardParameter) =
        val io = summon[Interface[ForwardIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val indexWidth = Gpr.indexWidth
        val entryCount = parameter.entryCount

        /** The slot's uop: the youngest writer in flight. Its identity is the slot's and its value the completion's —
          * the two ports are the same instruction, one naming the writer and the other carrying the value it produces.
          */
        val slotValid = io.issue.valid & (io.issue.bits.rd_index =/= 0.U(indexWidth))
        val slotArmed = slotValid & (if parameter.bypass.execute then io.exec.valid else false.B)
        io.offers.ref(0).valid := slotValid
        io.offers.ref(0).armed := slotArmed
        io.offers.ref(0).index := io.issue.bits.rd_index
        io.offers.ref(0).data := io.exec.bits.data

        /** The queue's entries, from the tail back to the head: offer `1 + k` is the entry at position `entryCount - 1
          * \- k`, so the column reads youngest first and a consumer never sees the ring.
          */
        if parameter.lsqDepth != 0 then
            val posW = log2Ceil(entryCount)
            val positions = (0 until entryCount).map(i => subWrap(i.U(posW), io.view.head, posW))

            def pickAt(position: Int): Seq[Referable[Bool]] =
                (0 until entryCount).map(j => positions(j) === position.U(posW))

            def oneHotBool(picks: Seq[Referable[Bool]], field: Int => Referable[Bool]): Referable[Bool] =
                picks.zipWithIndex.map((pick, j) => pick & field(j)).foldLeft(false.B: Referable[Bool])(_ | _)

            def oneHotUInt(
                picks: Seq[Referable[Bool]],
                field: Int => Referable[UInt],
                fieldWidth: Int
            ): Referable[UInt] =
                picks.zipWithIndex
                    .foldRight(0.U(fieldWidth): Referable[UInt]) { case ((pick, j), earlier) =>
                        pick ? (field(j), earlier)
                    }

            for k <- 0 until entryCount do
                val picks = pickAt(entryCount - 1 - k)
                val index = oneHotUInt(picks, j => io.view.entries.ref(j).rd_index, indexWidth)
                val valid = oneHotBool(picks, j => io.view.entries.ref(j).valid) & (index =/= 0.U(indexWidth))
                val armed =
                    valid & (if parameter.bypass.execute then oneHotBool(picks, j => io.view.entries.ref(j).done)
                             else false.B)
                val offer = io.offers.ref(1 + k)
                offer.valid := valid
                offer.armed := armed
                offer.index := index
                offer.data := oneHotUInt(picks, j => io.view.entries.ref(j).data, width)
        else
            // No queue: the column's entry slot is a constant, and the constant folds out of every consumer.
            val offer = io.offers.ref(1)
            offer.valid := false.B
            offer.armed := false.B
            offer.index := 0.U(indexWidth)
            offer.data := 0.U(width)

        /** The retiring write's verdict: the one producer the middle holds itself, so only the level's answer crosses —
          * the write-back register goes to the read directly.
          */
        io.commit_armed := (if parameter.bypass.execute | parameter.bypass.commit then true.B else false.B)
