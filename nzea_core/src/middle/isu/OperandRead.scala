package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.middle.exu.ForwardParameter
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Gpr, Isa, Operand, Srcs}
import nzea_core.middle.rf.ArfWrite
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class OperandReadParameter(isa: Isa, defaultPc: Long, lsqDepth: Int) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The forward column's length — the in-flight capacity's shape, shared with the network that broadcasts it. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[OperandReadParameter] = upickle.default.macroRW

class OperandReadLayers(parameter: OperandReadParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class OperandReadIO(parameter: OperandReadParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The payload whose operands are being placed, in the cycle ID/IS presents it. */
    val decoded = Flipped(new IduOut(parameter.config))

    /** The register file's read side: the indexes the payload names, the values the file answers with. */
    val rs1_index = Aligned(UInt(Gpr.indexWidth))
    val rs2_index = Aligned(UInt(Gpr.indexWidth))
    val rs1_data = Flipped(UInt(width))
    val rs2_data = Flipped(UInt(width))

    /** The forward network's column of in-flight writers, youngest first: the read resolves its own indexes against it
      * ([[nzea_rtl.stdlib.ForwardOffer]]).
      */
    val offers = Flipped(Vec(parameter.offerCount, new ForwardOffer(width, Gpr.indexWidth)))

    /** The retiring instruction's write — the middle's own producer, older than everything in the column — and whether
      * the level reaches it (the forward network's `commit_armed`).
      */
    val commit = Flipped(ValidIO(new ArfWrite(width)))
    val commit_armed = Flipped(Bool())

    /** The placed operands: the three slots the units read. */
    val src_a = Aligned(UInt(width))
    val src_b = Aligned(UInt(width))
    val src_c = Aligned(UInt(width))

    /** The read is placeable: every operand is settled — no in-flight writer is still in the way. */
    val ready = Aligned(Bool())

class OperandReadProbe(parameter: OperandReadParameter)
    extends DVBundle[OperandReadParameter, OperandReadLayers](parameter)

/** The read: the operand fetch between ID/IS and the files.
  *
  * The file answers the two indexes the payload names, the forward network's column answers them too — an offer that is
  * armed carries the value of the register's youngest writer, so it wins — and the operand form places the result, with
  * the immediate, the pc and zero, into the three slots the units read. The network's answer does not feed that
  * placement: a slot whose form reads Rs1/Rs2 takes the answer straight, past the resolve, so the forwarding path is
  * [offer, one mux] instead of [answer, a mux, the resolve].
  *
  * The column is the whole wait rule and it is read here: an offer that names the register without being armed is a
  * writer whose value is not there yet, so the read holds — and the operand form gates that: only a slot that reads the
  * register owes the wait. The retiring write is this side's own register, so it is matched here too: it is the oldest
  * writer in flight, the column's answer wins over it and the file's is the last resort. The read carries no state and
  * no query: it resolves whatever the network broadcasts this cycle, which is what makes it a wakeup consumer rather
  * than a requester.
  */
@generator
object OperandRead extends Generator[OperandReadParameter, OperandReadLayers, OperandReadIO, OperandReadProbe]:
    override def moduleName(parameter: OperandReadParameter): String = "OperandRead"

    def architecture(parameter: OperandReadParameter) =
        val io = summon[Interface[OperandReadIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width

        /** Whether a given slot's form reads the given register: one OR of the forms' bits. A slot that never holds the
          * operand contributes nothing, so an empty fold is false rather than an error.
          */
        def readsSlot(pick: Srcs => Operand, target: Operand): Referable[Bool] =
            val srcsBits: Node[Bits] = Node(io.decoded.srcs.asBits)
            Srcs.variants.zipWithIndex
                .collect { case (form, bit) if pick(form) == target => bit }
                .map(srcsBits.bit)
                .foldLeft(false.B: Referable[Bool])(_ | _)

        /** Whether any of the payload's slots reads the given register — what the network's wait term is gated by. */
        def readsRegister(target: Operand): Referable[Bool] =
            readsSlot(_.a, target) | readsSlot(_.b, target) | readsSlot(_.c, target)

        /** One read's answer, resolved against the column: the offers are in program order, so the value is the first
          * armed one that names the register — the column's own priority, no comparison — and a read holds while any
          * offer names the register without being armed. The retiring write is the oldest writer in flight, matched
          * from its own register here: it answers where the level reaches it and holds the read where it does not, and
          * it is the ladder's last resort before the file.
          */
        def answers(index: Referable[UInt]): Wake =
            val offers = (0 until parameter.offerCount).map(i => io.offers.ref(i))
            val armed = offers.map(offer => ForwardSource(offer.valid & offer.armed, offer.index, offer.data))
            val hit =
                armed.map(source => source.armed & (source.index === index)).foldLeft(false.B: Referable[Bool])(_ | _)
            val blocking = offers
                .map(offer => offer.valid & !offer.armed & (offer.index === index))
                .foldLeft(false.B: Referable[Bool])(_ | _)
            val commitValid = io.commit.valid & (io.commit.bits.index =/= 0.U(Gpr.indexWidth))
            val commitHit = commitValid & io.commit_armed & (io.commit.bits.index === index)
            val commitBlocks = commitValid & !io.commit_armed & (io.commit.bits.index === index)
            Wake(
                !(blocking | commitBlocks),
                hit | commitHit,
                forwardRead(index, commitHit ? (io.commit.bits.data, 0.U(width)), armed)
            )

        val wake1 = answers(io.rs1_index)
        val wake2 = answers(io.rs2_index)

        /** The read: the file answers the two indexes the payload names, an armed offer wins, and the operand form
          * places the result into the three slots the units read.
          */
        def placeOperands(): (Referable[UInt], Referable[UInt], Referable[UInt]) =
            val dec = io.decoded
            io.rs1_index := dec.rs1_index
            io.rs2_index := dec.rs2_index
            // The file's answer feeds the form resolve; the network's answer does not — a slot whose form reads Rs1/Rs2 takes
            // it straight. The hit terms are the forms that read that register in that slot: one OR of form bits each, off
            // the decode's register, so the forwarding path is [offer, one mux] and the resolve stays off it.
            val (srcA, srcB, srcC) = Srcs.resolve(
                dec.srcs,
                Seq[(Operand, Referable[UInt])](
                    Operand.Rs1 -> io.rs1_data,
                    Operand.Rs2 -> io.rs2_data,
                    Operand.Imm -> dec.imm,
                    Operand.Pc -> dec.pc,
                    Operand.Zero -> 0.U(width)
                ),
                width
            )
            val bypassA = readsSlot(_.a, Operand.Rs1) & wake1.from_net
            val bypassB = readsSlot(_.b, Operand.Rs2) & wake2.from_net
            val bypassC = readsSlot(_.c, Operand.Rs2) & wake2.from_net
            (
                bypassA ? (wake1.value, srcA),
                bypassB ? (wake2.value, srcB),
                bypassC ? (wake2.value, srcC)
            )

        /** The read is placeable when neither of its registers is held back — the operand form is the only part left
          * here: a slot no register feeds owes no wait.
          */
        def operandsReady(): Referable[Bool] =
            val reads1 = readsRegister(Operand.Rs1)
            val reads2 = readsRegister(Operand.Rs2)
            !(reads1 & !wake1.ready) & !(reads2 & !wake2.ready)

        val (srcA, srcB, srcC) = placeOperands()
        io.src_a := srcA
        io.src_b := srcB
        io.src_c := srcC
        io.ready := operandsReady()
