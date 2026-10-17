package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.isa.{CsrType, Gpr, Isa}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class ForwardParameter(isa: Isa, defaultPc: Long, bypass: BypassLevel, lsqDepth: Int) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The queue's entries as this module reads them; a single dummy slot when no queue is built (`lsqDepth` zero), so
      * the column has a shape either way.
      */
    val entryCount: Int = ForwardParameter.entryCount(lsqDepth)

    /** Each column's length: the slot's writer and the entries. The retiring writes are not in it — they are the
      * middle's.
      */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[ForwardParameter] = upickle.default.macroRW

object ForwardParameter:

    def entryCount(lsqDepth: Int): Int = if lsqDepth == 0 then 1 else lsqDepth

    def offerCount(lsqDepth: Int): Int = entryCount(lsqDepth) + 1

/** The forward network's face, as the middle sees it: the two columns of in-flight writers — the register file's and
  * the CSR file's — and the level's verdict for the retiring writes the middle holds itself.
  *
  * One port, because it is one thing: the network's answer to the read. The columns are
  * [[nzea_rtl.stdlib.ForwardOffer]]s; the verdict is a constant for a configuration, and it rides here so a consumer
  * never has to know the level.
  */
class ForwardBus(width: Int, offerCount: Int) extends Bundle:
    val arf_offers = Aligned(Vec(offerCount, new ForwardOffer(width, Gpr.indexWidth)))
    val csr_offers = Aligned(Vec(offerCount, new ForwardOffer(width, CsrType.width)))
    val commit_armed = Aligned(Bool())

class ForwardLayers(parameter: ForwardParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ForwardIO(parameter: ForwardParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The slot's uop as a value: whether it is finishing this cycle, and the value the integer path produced for it —
      * the only live value a read can take. A memory op's live answer is never forwardable: a load's data arrives after
      * its slot has moved on (its queue entry answers the read) and a store writes nothing, so taking the memory result
      * as a source here would only drag its request cone into every read's loop.
      */
    val exec = Flipped(ValidIO(new PathResult(width)))

    val csr_offer = Flipped(ValidIO(new CsrOffer(width)))

    val issue = Flipped(new IssueSlot(new ExecSlot(parameter.config)))

    /** The queue as this network reads it: the writers between the completing instruction and the retiring write, in
      * program order — the head is the oldest, the position `(index - head) mod depth` the age.
      */
    val view = Flipped(new QueueView(width, parameter.entryCount))

    val forward = Aligned(new ForwardBus(width, parameter.offerCount))

class ForwardProbe(parameter: ForwardParameter) extends DVBundle[ForwardParameter, ForwardLayers](parameter)

/** The forward network: the in-flight writers the execution face holds gathered into two columns of offers — the
  * register file's and the CSR file's — youngest first, each cut to the level's reach.
  *
  * The order is program order — the slot's uop is the youngest writer in flight, the queue's entries read back from the
  * tail behind it — so a match needs no tag: the first offer that answers a register, or a CSR code, is its youngest
  * writer. The producers do not know who reads them and the consumers do not know who produces: a column is the whole
  * contract, and a consumer resolves its own read against it. The middle's retiring writes are older than everything
  * here and live in the middle, so they are not in the columns: only the level's verdict for them crosses.
  *
  * The level is a column's `armed` bit: a writer the level cannot forward — the completing value and the entries below
  * `Execute` — is broadcast unarmed, which is what keeps a read that names it waiting.
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

        /** The slot's uop: the youngest writer in flight — the identity from this port, the value from `exec`, the two
          * being the same instruction.
          */
        val slotValid = io.issue.valid & (io.issue.bits.rd_index =/= 0.U(indexWidth))
        val slotArmed = slotValid & (if parameter.bypass.execute then io.exec.valid else false.B)
        io.forward.arf_offers.ref(0).valid := slotValid
        io.forward.arf_offers.ref(0).armed := slotArmed
        io.forward.arf_offers.ref(0).index := io.issue.bits.rd_index
        io.forward.arf_offers.ref(0).data := io.exec.bits.data

        /** The slot's CSR offer, the same youngest writer in the CSR's index space: it is ready with the uop, because
          * the sysu is combinational. What the commit makes of it (a zero-source CSRRS/CSRRC stores nothing) is the
          * commit's refinement, not the read's.
          */
        val csrSlotValid = io.issue.valid & io.csr_offer.valid
        val csrSlotArmed = csrSlotValid & (if parameter.bypass.execute then true.B else false.B)
        io.forward.csr_offers.ref(0).valid := csrSlotValid
        io.forward.csr_offers.ref(0).armed := csrSlotArmed
        io.forward.csr_offers.ref(0).index := io.csr_offer.bits.csr_type
        io.forward.csr_offers.ref(0).data := io.csr_offer.bits.data

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
                val offer = io.forward.arf_offers.ref(1 + k)
                offer.valid := valid
                offer.armed := armed
                offer.index := index
                offer.data := oneHotUInt(picks, j => io.view.entries.ref(j).data, width)

                val csrType = oneHotUInt(picks, j => io.view.entries.ref(j).csr_type, CsrType.width)
                val csrValid = oneHotBool(picks, j => io.view.entries.ref(j).valid) &
                    (csrType =/= CsrType.None.lit.U(CsrType.width))
                val csrArmed =
                    csrValid & (if parameter.bypass.execute then oneHotBool(picks, j => io.view.entries.ref(j).done)
                                else false.B)
                val csrOffer = io.forward.csr_offers.ref(1 + k)
                csrOffer.valid := csrValid
                csrOffer.armed := csrArmed
                csrOffer.index := csrType
                csrOffer.data := oneHotUInt(picks, j => io.view.entries.ref(j).csr_data, width)
        else
            val offer = io.forward.arf_offers.ref(1)
            offer.valid := false.B
            offer.armed := false.B
            offer.index := 0.U(indexWidth)
            offer.data := 0.U(width)
            val csrOffer = io.forward.csr_offers.ref(1)
            csrOffer.valid := false.B
            csrOffer.armed := false.B
            csrOffer.index := 0.U(CsrType.width)
            csrOffer.data := 0.U(width)

        /** The retiring writes' verdict: the producers the middle holds itself, so only the level's answer crosses.
          */
        io.forward.commit_armed := (if parameter.bypass.execute | parameter.bypass.commit then true.B else false.B)
