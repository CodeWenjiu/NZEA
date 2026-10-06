package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.{ForwardParameter, IssueSlot, IssueUop}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Gpr, Isa}
import nzea_core.middle.ExecSlot
import nzea_core.middle.rf.{ArfReadPort, ArfWrite}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IssueStageParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    lsqDepth: Int,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    /** The forward column's length — the in-flight capacity's shape, shared with the network that broadcasts it. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[IssueStageParameter] = upickle.default.macroRW

class IssueStageLayers(parameter: IssueStageParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IssueStageIO(parameter: IssueStageParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val decoded = Flipped(Decoupled(new IduOut(parameter.config)))

    /** The flush event the write-back stage sources; the stage registers refuse a handover in its cycle. */
    val flush = Flipped(new FlushBus(width))

    /** The execute slot's pace: the cycle the slot's uop is finished with the slot. Without a queue that is the
      * completion; with one it is the slot release — the memory request's acceptance, or a unit's completion.
      */
    val slotRelease = Flipped(Bool())

    /** The CSR read the slot is answered with for its whole residence. */
    val csr_rdata = Flipped(UInt(width))

    /** The register file's read side, from the reader's view: the indexes go out, the values come back. */
    val arf_rs1 = Flipped(new ArfReadPort(width))
    val arf_rs2 = Flipped(new ArfReadPort(width))

    /** The forward network's column of in-flight writers, youngest first: the read resolves its own indexes against it
      * ([[nzea_rtl.stdlib.ForwardOffer]]).
      */
    val offers = Flipped(Vec(parameter.offerCount, new ForwardOffer(width, Gpr.indexWidth)))

    /** The retiring instruction's write, from the write-back stage: the read's own producer — matched there — and
      * whether the level reaches it, from the forward bus.
      */
    val commit = Flipped(ValidIO(new ArfWrite(width)))
    val commit_armed = Flipped(Bool())

    val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))

    /** The CSR address the slot's read port is addressed with for its whole residence. */
    val slot_csr_addr = Aligned(UInt(12))

class IssueStageProbe(parameter: IssueStageParameter) extends DVBundle[IssueStageParameter, IssueStageLayers](parameter)

/** The issue stage: the middle's two elastic registers with the read and the assembly between them.
  *
  * The ID/IS and IS/EX registers are owned here, with [[nzea_core.middle.isu.OperandRead]] (the operand fetch) and
  * [[nzea_core.middle.isu.Isu]] (the assembly of the issued uop) between them, both combinational — the same rule the
  * front-end follows around its decode. The writers a read must wait for are not this stage's to know: the read
  * resolves the forward network's column itself and moves on its own verdict. The uop is what the units work on for the
  * instruction's whole residence, `first` marking the arrival cycle.
  *
  * The stage's own counters (`operand_wait`, `slot_busy`) are declared here, where its registers are.
  */
@generator
object IssueStage extends Generator[IssueStageParameter, IssueStageLayers, IssueStageIO, IssueStageProbe]:
    override def moduleName(parameter: IssueStageParameter): String = "IssueStage"

    def architecture(parameter: IssueStageParameter) =
        val io = summon[Interface[IssueStageIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        // ID/IS and IS/EX: the middle's two elastic stage registers; the issue unit between them is combinational. Both
        // refuse a handover in the flush cycle: their producer is a peer, and taking a payload on a path that is being
        // killed would only make the fetcher ask the bus for a word nobody keeps.
        val idis = Wire(Decoupled(new IduOut(parameter.config)))
        val feed = Wire(Decoupled(new ExecSlot(parameter.config)))
        val isex = Wire(Decoupled(new ExecSlot(parameter.config)))
        pipelineConnect(io.decoded, idis, io.flush, "idis", FlushHandover.Refuse)
        pipelineConnect(feed, isex, io.flush, "isex", FlushHandover.Refuse)
        // The execute slot's arrival edge: the units take the uop as a level and need the cycle it began.
        val slotFirst = RegInit(false.B)

        // The slot's CSR address out to the write-back stage: the address its read port is addressed with for the whole
        // residence.
        io.slot_csr_addr := isex.bits.csr_addr

        val read =
            OperandRead.instantiate(OperandReadParameter(parameter.isa, parameter.defaultPc, parameter.lsqDepth))
        val isu = Isu.instantiate(IsuParameter(parameter.isa, parameter.defaultPc))

        /** The read: the payload's indexes out to the file and the network, the answers back in, and the placement of
          * the three operand slots, with the pace this read can move at.
          */
        def readUnit(): Unit =
            read.io.clock := io.clock
            read.io.resetN := io.resetN
            read.io.decoded :<= idis.bits
            read.io.rs1_data := io.arf_rs1.data
            read.io.rs2_data := io.arf_rs2.data
            io.arf_rs1.index := read.io.rs1_index
            io.arf_rs2.index := read.io.rs2_index

            // The forward column: the read resolves its own indexes against the offers, and the retiring write — the
            // middle's own register — against the level's verdict for it.
            read.io.offers :<= io.offers
            read.io.commit :<= io.commit
            read.io.commit_armed := io.commit_armed

        /** The issued uop: the read's operands and feasibility, the payload's held fields, and the CSR read. */
        def issueUnit(): Unit =
            isu.io.clock := io.clock
            isu.io.resetN := io.resetN
            isu.io.decoded :<= idis.bits
            idis.ready := isu.io.decoded_ready
            isu.io.src_a := read.io.src_a
            isu.io.src_b := read.io.src_b
            isu.io.src_c := read.io.src_c
            isu.io.operands_ready := read.io.ready
            isu.io.slot :<= isex.bits
            isu.io.slot_valid := isex.valid
            isu.io.slot_first := slotFirst
            isu.io.slotRelease := io.slotRelease
            isu.io.flush :<= io.flush
            isu.io.csr_rdata := io.csr_rdata
            io.issue :<= isu.io.issue

        readUnit()
        issueUnit()

        // The read's uop into IS/EX: handed over in the cycle ID/IS is consumed, taken when the units are done with the
        // previous one — the cycle they release it.
        feed.valid := idis.fire
        feed.bits :<= isu.io.moved
        isex.ready := io.slotRelease
        slotFirst := feed.fire

        /** Counters the simulator reads back: the payload's pace and the slot's occupancy, declared here where both
          * registers are.
          */
        def counters(): Unit =
            val wait =
                parameter.stats.stat("operand_wait", Regions.Throughput, "cycles a payload waits for its own operands")
            val busy =
                parameter.stats.stat("slot_busy", Regions.Throughput, "cycles the execute slot holds an unfinished uop")
            wait.inc(idis.valid & !idis.ready & (!isex.valid | io.slotRelease) & !io.flush.valid)
            busy.inc(isex.valid & !io.slotRelease)

        if parameter.flow.counters then counters()
