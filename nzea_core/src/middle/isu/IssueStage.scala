package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.back.{IssueSlot, IssueUop}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Gpr, Isa}
import nzea_core.middle.ExecSlot
import nzea_core.middle.rf.ArfReadPort
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IssueStageParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    bypass: BypassLevel,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IssueStageParameter] = upickle.default.macroRW

class IssueStageLayers(parameter: IssueStageParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IssueStageIO(parameter: IssueStageParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val decoded = Flipped(Decoupled(new IduOut(parameter.config)))

    /** The flush the write-back stage sources; the stage registers refuse a handover in its cycle. */
    val flush = Flipped(Bool())

    /** The completion as the read sees it: the cycle the execute slot frees. */
    val complete = Flipped(Bool())

    /** The retiring write: the writer the read waits for unless the bypass level reaches its stage. */
    val wb_valid = Flipped(Bool())
    val wb_rd = Flipped(UInt(Gpr.indexWidth))
    val csr_rdata = Flipped(UInt(width))

    /** The register file's read side, from the reader's view: the indexes go out, the values come back. */
    val arf_rs1 = Flipped(new ArfReadPort(width))
    val arf_rs2 = Flipped(new ArfReadPort(width))

    val bypass_rs1_index = Aligned(UInt(Gpr.indexWidth))
    val bypass_rs2_index = Aligned(UInt(Gpr.indexWidth))
    val bypass_rs1 = Flipped(ValidIO(UInt(width)))
    val bypass_rs2 = Flipped(ValidIO(UInt(width)))

    val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))

    /** The slot's identity, out to the write-back stage: the destination it latches and the CSR address it reads. */
    val slot_rd_index = Aligned(UInt(Gpr.indexWidth))
    val slot_csr_addr = Aligned(UInt(12))

class IssueStageProbe(parameter: IssueStageParameter) extends DVBundle[IssueStageParameter, IssueStageLayers](parameter)

/** The issue stage: the middle's two elastic registers with the read and the assembly between them.
  *
  * The ID/IS and IS/EX registers are owned here, with [[nzea_core.middle.isu.OperandRead]] (the operand fetch and the
  * in-flight writers it must wait for) and [[nzea_core.middle.isu.Isu]] (the assembly of the issued uop) between them,
  * both combinational — the same rule the front-end follows around its decode. The writers it waits for are the execute
  * slot's instruction and the write-back stage's retiring one; the level it hands on is what the units work on for the
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

        // The slot's identity out to the write-back stage: the destination its retire register latches with the
        // completion, and the CSR address its read port is addressed with for the whole residence.
        io.slot_rd_index := isex.bits.rd_index
        io.slot_csr_addr := isex.bits.csr_addr

        val read =
            OperandRead.instantiate(OperandReadParameter(parameter.isa, parameter.defaultPc, parameter.bypass))
        val isu = Isu.instantiate(IsuParameter(parameter.isa, parameter.defaultPc))

        /** The read: the payload's indexes out to the file and the network, the answers back in, and the placement of
          * the three operand slots, with the pace this read can move at.
          */
        def readUnit(): Unit =
            read.io.clock := io.clock
            read.io.resetN := io.resetN
            read.io.decoded :<= idis.bits
            read.io.slot_valid := isex.valid
            read.io.slot_rd_index := isex.bits.rd_index
            read.io.wb_valid := io.wb_valid
            read.io.wb_rd := io.wb_rd
            read.io.rs1_data := io.arf_rs1.data
            read.io.rs2_data := io.arf_rs2.data
            io.arf_rs1.index := read.io.rs1_index
            io.arf_rs2.index := read.io.rs2_index

            // The bypass bus: the read's indexes out to the network, its answers back into the read's select.
            io.bypass_rs1_index := read.io.rs1_index
            io.bypass_rs2_index := read.io.rs2_index
            read.io.bypass_rs1 :<= io.bypass_rs1
            read.io.bypass_rs2 :<= io.bypass_rs2

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
            isu.io.complete := io.complete
            isu.io.flush := io.flush
            isu.io.csr_rdata := io.csr_rdata
            io.issue :<= isu.io.issue

        readUnit()
        issueUnit()

        // The read's uop into IS/EX: handed over in the cycle ID/IS is consumed, taken when the units are done with the
        // previous one — the cycle they report it complete.
        feed.valid := idis.fire
        feed.bits :<= isu.io.moved
        isex.ready := io.complete
        slotFirst := feed.fire

        /** Counters the simulator reads back: the payload's pace and the slot's occupancy, declared here where both
          * registers are.
          */
        def counters(): Unit =
            val wait =
                parameter.stats.stat("operand_wait", Regions.Throughput, "cycles a payload waits for its own operands")
            val busy =
                parameter.stats.stat("slot_busy", Regions.Throughput, "cycles the execute slot holds an unfinished uop")
            wait.inc(idis.valid & !idis.ready & (!isex.valid | io.complete) & !io.flush)
            busy.inc(isex.valid & !io.complete)

        if parameter.flow.counters then counters()
