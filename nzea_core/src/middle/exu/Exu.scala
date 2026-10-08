package nzea_core.middle.exu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.{IntFace, IssueSlot, IssueUop, MemFace, QueueFace, RetireEvent}
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_core.middle.ExecSlot
import nzea_core.middle.rf.CsrReadPort
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.{FlushBus, ForwardOffer}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class ExuParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    bypass: BypassLevel,
    lsqDepth: Int,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The forward column's shape — the in-flight capacity's, shared with the network that broadcasts it. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[ExuParameter] = upickle.default.macroRW

class ExuLayers(parameter: ExuParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ExuIO(parameter: ExuParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The IS/EX register's read side, this stage's entry: the slot as a level, its arrival edge, and the flush event
      * that silences it.
      */
    val slot = Flipped(Decoupled(new ExecSlot(parameter.config)))
    val slot_first = Flipped(Bool())
    val flush = Flipped(new FlushBus(width))

    /** The CSR file's read side, from the reader's view: the address the slot holds goes out, the value comes back. */
    val csr_read = Flipped(new CsrReadPort(width))

    /** The execution face, up to the core. */
    val int = Aligned(new IntFace(parameter.config))
    val mem = Aligned(new MemFace(parameter.config))
    val queue = Aligned(new QueueFace(parameter.config))

    /** What the stages consume: the retirement and the forward column with the retiring write's verdict. */
    val retire = Aligned(ValidIO(new RetireEvent(width)))
    val offers = Aligned(Vec(parameter.offerCount, new ForwardOffer(width, Gpr.indexWidth)))
    val commit_armed = Aligned(Bool())

class ExuProbe(parameter: ExuParameter) extends DVBundle[ExuParameter, ExuLayers](parameter)

/** The execute stage: the middle's face to the execution paths, and what a finished instruction needs before the
  * write-back register can take it.
  *
  * This stage reads the IS/EX register: the slot's payload, presented as the backend's uop with the CSR read answered
  * here, and the roles its arrival and the flush give it. [[Dispatch]] spends decode's `Backend` on both the routing
  * and the merge; [[Completion]] turns the merge's conclusion into the retire stream and the slot's pace, and holds the
  * queue that outlives the execute slot; [[Forward]] gathers the in-flight writers into the column the read resolves.
  * Their wires are this module's own — the retire and the pace do not leave it — and what crosses its boundary is the
  * neighbouring stages' business: the slot's read side and the flush in, the retirement and the column out, and the
  * execution face up to the core.
  */
@generator
object Exu extends Generator[ExuParameter, ExuLayers, ExuIO, ExuProbe]:
    override def moduleName(parameter: ExuParameter): String = "Exu"

    def architecture(parameter: ExuParameter) =
        val io = summon[Interface[ExuIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val dispatch = Dispatch.instantiate(DispatchParameter(c.isa, c.defaultPc))
        val completion =
            Completion.instantiate(
                CompletionParameter(c.isa, c.defaultPc, parameter.flow, parameter.lsqDepth, parameter.stats)
            )
        val forward =
            Forward.instantiate(ForwardParameter(c.isa, c.defaultPc, parameter.bypass, parameter.lsqDepth))

        dispatch.io.clock := io.clock
        dispatch.io.resetN := io.resetN
        completion.io.clock := io.clock
        completion.io.resetN := io.resetN
        forward.io.clock := io.clock
        forward.io.resetN := io.resetN

        // The slot's read side: the completion path's pace is the register's drain, and the CSR read is addressed by
        // the slot, which holds the address for its whole residence.
        io.slot.ready := completion.io.pace
        io.csr_read.addr := io.slot.bits.csr_addr

        val view = Wire(new IssueSlot(new IssueUop(c)))

        /** The uop the units work on, as the slot presents it: the payload reversed into the backend's contract, with
          * the CSR read answered here. A memory instruction's `inst[31:20]` is an address offset, not a CSR address, so
          * it maps to none, and a flush silences the roles — the units take a vanished uop as abandoned.
          */
        def issueView(): Unit =
            val slot = io.slot.bits
            val uop = Wire(new IssueUop(c))
            uop.pc := slot.pc
            uop.next_pc := slot.next_pc
            uop.fall_through := slot.fall_through
            uop.pht_count := slot.pht_count
            uop.imm := slot.imm
            uop.src_a := slot.src_a
            uop.src_b := slot.src_b
            uop.src_c := slot.src_c
            uop.backend := slot.backend
            uop.op := slot.op
            uop.rd_index := slot.rd_index
            val isMem = slot.backend === Backend.MEM.lit.U(Backend.width)
            uop.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), CsrType.fromAddr(slot.csr_addr))
            uop.csr_rdata := io.csr_read.data
            view.valid := io.slot.valid
            view.first := io.slot_first & !io.flush.valid
            view.bits :<= uop

        issueView()

        // The execution face: the boundary and the dispatch carry the same faces, and the queue is the completion
        // path's — one connection per face, so the two ends cannot be paired up wrong.
        io.int :<>= dispatch.io.int
        io.mem :<>= dispatch.io.mem
        io.queue :<>= completion.io.queue

        // The dispatch and the completion path: the view is shared between them, the merge's conclusion flows from the
        // first to the second.
        dispatch.io.issue :<= view
        completion.io.issue :<= view
        completion.io.merged :<>= dispatch.io.merged
        completion.io.flush :<= io.flush

        // The forward network's producers: the completing instruction, the slot's own writer, the queue's entries.
        forward.io.exec :<= dispatch.io.merged.complete
        forward.io.issue :<= view
        forward.io.view :<>= completion.io.view

        // What the stages consume.
        io.retire :<= completion.io.retire
        io.offers :<= forward.io.offers
        io.commit_armed := forward.io.commit_armed
