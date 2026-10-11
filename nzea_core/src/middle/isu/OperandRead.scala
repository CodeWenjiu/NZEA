package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.{ForwardBus, ForwardParameter}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{CsrType, Gpr, Isa, Operand, Srcs}
import nzea_core.middle.rf.CsrPort
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

    /** The register file's read side: the indexes the payload names, the values the file answers with, and the RF's own
      * commit view — the hit its write port makes on each index.
      */
    val rs1_index = Aligned(UInt(Gpr.indexWidth))
    val rs2_index = Aligned(UInt(Gpr.indexWidth))
    val rs1_data = Flipped(UInt(width))
    val rs2_data = Flipped(UInt(width))
    val rs1_commit_hit = Flipped(Bool())
    val rs2_commit_hit = Flipped(Bool())

    /** The CSR file's read side, through the RF's port: the address the payload names, the code it maps to, the value
      * the file answers with, and the same commit view.
      */
    val csr_read = Flipped(new CsrPort(width))

    /** The forward network's face: the columns of in-flight writers this read resolves its own indexes against, and the
      * level's verdict for the retiring writes ([[nzea_core.back.ForwardBus]]).
      */
    val forward = Flipped(new ForwardBus(width, parameter.offerCount))

    /** The placed operands: the four slots the units read. */
    val src_a = Aligned(UInt(width))
    val src_b = Aligned(UInt(width))
    val src_c = Aligned(UInt(width))
    val src_d = Aligned(UInt(width))

    /** The CSR read's resolution: the code the payload's address names — none unless the form names the CSR, so the
      * code rides only where it means something.
      */
    val csr_type = Aligned(UInt(CsrType.width))

    /** The read is placeable: every operand is settled — no in-flight writer is still in the way. */
    val ready = Aligned(Bool())

class OperandReadProbe(parameter: OperandReadParameter)
    extends DVBundle[OperandReadParameter, OperandReadLayers](parameter)

/** The read: the operand fetch between ID/IS and the files.
  *
  * The file answers the two indexes the payload names, the forward network's column answers them too — an offer that is
  * armed carries the value of the register's youngest writer, so it wins — and the operand form places the result, with
  * the immediate, the pc and zero, into the four slots the units read. The network's answer does not feed that
  * placement: a slot whose form reads Rs1/Rs2 takes the answer straight, past the resolve, so the forwarding path is
  * [offer, one mux] instead of [answer, a mux, the resolve].
  *
  * The column is the whole wait rule and it is read here: an offer that names the register without being armed is a
  * writer whose value is not there yet, so the read holds — and the operand form gates that: only a slot that reads the
  * register owes the wait. The retiring write is the oldest writer in flight, and the RF names it: its `commit_hit`
  * arrives beside the file's answer (which is write-through), and the level's verdict is all this read owes it — taken
  * when armed, waited on when not. The read carries no state and no query: it resolves whatever the network broadcasts
  * this cycle, which is what makes it a wakeup consumer rather than a requester.
  *
  * The CSR file's read is the same fetch in a second index space: the payload's address maps to its
  * [[nzea_core.isa.CsrType]] code — the code is what the writers carry, because a queue entry keeps the type and not
  * the address — and that code resolves against the CSR half of the column and the RF's answer, in that order. The sysu
  * is the only consumer, so only a form that names the CSR owes this wait and carries the code: the address field of
  * any other instruction is not an address at all. The value rides to the payload as a placed slot, resolved at the
  * issue — which is what leaves the execute stage with nothing to ask.
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
            readsSlot(_.a, target) | readsSlot(_.b, target) | readsSlot(_.c, target) | readsSlot(_.d, target)

        /** One read's answer, resolved against the column: the offers are in program order, so the value is the first
          * armed one that names the register — the column's own priority, no comparison — and a read holds while any
          * offer names the register without being armed. The commit's own write is the RF's to name: its hit arrives
          * with the port, the level's verdict decides whether the read takes it or waits, and the port's value (the
          * file, write-through) is the last resort behind the column.
          */
        def answers(index: Referable[UInt], commitHit: Referable[Bool]): Wake =
            val offers = (0 until parameter.offerCount).map(i => io.forward.arf_offers.ref(i))
            val armed = offers.map(offer => ForwardSource(offer.valid & offer.armed, offer.index, offer.data))
            val hit =
                armed.map(source => source.armed & (source.index === index)).foldLeft(false.B: Referable[Bool])(_ | _)
            val blocking = offers
                .map(offer => offer.valid & !offer.armed & (offer.index === index))
                .foldLeft(false.B: Referable[Bool])(_ | _)
            val commitBlocks = commitHit & !io.forward.commit_armed
            Wake(!(blocking | commitBlocks), hit, forwardRead(index, 0.U(width), armed))

        val wake1 = answers(io.rs1_index, io.rs1_commit_hit)
        val wake2 = answers(io.rs2_index, io.rs2_commit_hit)

        /** The code the payload's address names, as the RF resolves it — the reader's key in the CSR index space, and
          * the field the execute stage's sysu takes the CSR by.
          */
        val csrCode: Referable[UInt] = io.csr_read.code

        /** The CSR read's answer, resolved the same way in its own index space: the column first — the first armed
          * offer that names the code is its youngest writer — and the RF's answer (the file plus this cycle's commit)
          * last. Only a sysu op owes the wait: the sysu is the only unit that reads the CSR, and the address field of
          * any other instruction is not an address.
          */
        def csrAnswers(): Wake =
            val offers = (0 until parameter.offerCount).map(i => io.forward.csr_offers.ref(i))
            val armed = offers.map(offer => ForwardSource(offer.valid & offer.armed, offer.index, offer.data))
            val hit =
                armed.map(source => source.armed & (source.index === csrCode)).foldLeft(false.B: Referable[Bool])(_ | _)
            val blocking = offers
                .map(offer => offer.valid & !offer.armed & (offer.index === csrCode))
                .foldLeft(false.B: Referable[Bool])(_ | _)
            val csrCommitBlocks = io.csr_read.commit_hit & !io.forward.commit_armed
            Wake(!(blocking | csrCommitBlocks), hit, forwardRead(csrCode, 0.U(width), armed))

        val csrWake = csrAnswers()

        /** Whether this payload runs the sysu — the one unit that reads the CSR, so the one that owes its wait. The
          * form says it: only a CSR instruction's slots name [[Operand.Csr]].
          */
        val readsCsr = readsRegister(Operand.Csr)

        /** The read: the file answers the two indexes the payload names, an armed offer wins, and the operand form
          * places the result into the four slots the units read.
          */
        def placeOperands(): (Referable[UInt], Referable[UInt], Referable[UInt], Referable[UInt]) =
            val dec = io.decoded
            io.rs1_index := dec.rs1_index
            io.rs2_index := dec.rs2_index
            // The file's answer feeds the form resolve; the network's answer does not — a slot whose form reads Rs1/Rs2 takes
            // it straight. The hit terms are the forms that read that register in that slot: one OR of form bits each, off
            // the decode's register, so the forwarding path is [offer, one mux] and the resolve stays off it. Which read a
            // slot takes is the form's own answer — the form decides where each register goes — so the wake follows the
            // operand the slot names, never a fixed slot.
            val (srcA, srcB, srcC, srcD) = Srcs.resolve(
                dec.srcs,
                Seq[(Operand, Referable[UInt])](
                    Operand.Rs1 -> io.rs1_data,
                    Operand.Rs2 -> io.rs2_data,
                    Operand.Imm -> dec.imm,
                    Operand.Pc -> dec.pc,
                    Operand.Zero -> 0.U(width),
                    Operand.Csr -> io.csr_read.data
                ),
                width
            )

            /** One slot placed: the file's answer, or the wake of the register the form names for that slot. A slot
              * names one source, so the tests are exclusive and the muxes are one select.
              */
            def placeSlot(pick: Srcs => Operand, src: Referable[UInt]): Referable[UInt] =
                val fromRs1 = readsSlot(pick, Operand.Rs1) & wake1.from_net
                val fromRs2 = readsSlot(pick, Operand.Rs2) & wake2.from_net
                val fromCsr = readsSlot(pick, Operand.Csr) & csrWake.from_net
                fromRs1 ? (wake1.value, fromRs2 ? (wake2.value, fromCsr ? (csrWake.value, src)))

            (placeSlot(_.a, srcA), placeSlot(_.b, srcB), placeSlot(_.c, srcC), placeSlot(_.d, srcD))

        /** The read is placeable when nothing it reads is held back — the operand form is the only gate: a slot no
          * register or CSR feeds owes no wait.
          */
        def operandsReady(): Referable[Bool] =
            val reads1 = readsRegister(Operand.Rs1)
            val reads2 = readsRegister(Operand.Rs2)
            !(reads1 & !wake1.ready) & !(reads2 & !wake2.ready) & !(readsCsr & !csrWake.ready)

        val (srcA, srcB, srcC, srcD) = placeOperands()
        io.src_a := srcA
        io.src_b := srcB
        io.src_c := srcC
        io.src_d := srcD
        io.csr_read.addr := io.decoded.csr_addr
        io.csr_type := readsCsr ? (csrCode, CsrType.None.lit.U(CsrType.width))
        io.ready := operandsReady()
