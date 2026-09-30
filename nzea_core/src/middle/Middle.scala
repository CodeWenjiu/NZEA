package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, Valid, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BypassLevel, CoreConfig}
import nzea_core.back.{BypassBus, IssueSlot, IssueUop, PathResult}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Gpr, Isa}
import nzea_core.middle.isu.{Isu, IsuParameter, OperandRead, OperandReadParameter}
import nzea_core.stats.Regions
import nzea_rtl.{RtlFlow, StatScope}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class MiddleParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, bypass: BypassLevel, stats: StatScope)
    extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[MiddleParameter] = upickle.default.macroRW

class MiddleLayers(parameter: MiddleParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class MiddleIO(parameter: MiddleParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val decoded = Flipped(Decoupled(new IduOut(parameter.config)))
  val redirect = Aligned(Valid(UInt(width)))
  val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))
  val complete = Flipped(ValidIO(new PathResult(width)))
  val bypass = Aligned(new BypassBus(width))
  val commit = Aligned(ValidIO(new CommitMsg(width)))
  val flush = Aligned(Bool())

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: the issue stage between its two elastic registers, the files it reads, and the write-back stage the
  * commit and the flush are.
  *
  * The ID/IS and IS/EX registers are owned here, with [[nzea_core.middle.isu.OperandRead]] (the operand fetch and the
  * in-flight writers it must wait for) and [[nzea_core.middle.isu.Isu]] (the assembly of the issued uop) between them,
  * both combinational — the same rule the front-end follows around its decode. The register and CSR files stay here too
  * — their reads belong to issue and their writes to write-back, so both sides touch them and this is where they live —
  * as does EX/WB, which is neither elastic nor cancellable: the completion lands in it, the register write, the CSR
  * write and the commit ride it, and a mispredicted branch in it — `next_pc` against the prediction it was fetched
  * under — is what cancels the younger stages. The flush is sourced here, from that register, so every stage reads one
  * net instead of asking the stage below.
  */
@generator
object Middle extends Generator[MiddleParameter, MiddleLayers, MiddleIO, MiddleProbe]:
  override def moduleName(parameter: MiddleParameter): String = "Middle"

  /** The block name the middle's statistics are attributed to; the parent's scope takes it as a segment. */
  val Source: String = "middle"

  def architecture(parameter: MiddleParameter) =
    val io = summon[Interface[MiddleIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val indexWidth = Gpr.indexWidth

    // EX/WB: the one register here that is neither elastic nor cancellable — what the units completed retires from it,
    // and a mispredicted branch in it is what cancels the younger stages. The flush is sourced from it, so every stage
    // reads one net instead of asking the stage below, and the prediction check that starts the flush is made against
    // it as well: both sides of `next_pc =/= pred_next_pc` are registers here, out of the back's combinational path.
    val wb = Reg(new PathResult(width))
    val wbValid = RegInit(false.B)
    val wbRd = Reg(UInt(indexWidth))
    val complete = io.complete.valid
    val retire = wbValid
    val mispredicted = wb.is_branch & (wb.next_pc =/= wb.pred_next_pc)
    val flushing = retire & mispredicted

    val csr = CsrFile.instantiate(CsrFileParameter(width))
    csr.io.clock := io.clock
    csr.io.resetN := io.resetN

    val arf = Arf.instantiate(ArfParameter(width))
    arf.io.clock := io.clock
    arf.io.resetN := io.resetN

    // ID/IS and IS/EX: the middle's two elastic stage registers; the issue unit between them is combinational. Both
    // refuse a handover in the flush cycle: their producer is a peer, and taking a payload on a path that is being
    // killed would only make the fetcher ask the bus for a word nobody keeps.
    val idis = Wire(Decoupled(new IduOut(parameter.config)))
    val feed = Wire(Decoupled(new ExecSlot(parameter.config)))
    val isex = Wire(Decoupled(new ExecSlot(parameter.config)))
    pipelineConnect(io.decoded, idis, flushing, "idis", FlushHandover.Refuse)
    pipelineConnect(feed, isex, flushing, "isex", FlushHandover.Refuse)
    // The execute slot's arrival edge: the units take the uop as a level and need the cycle it began.
    val slotFirst = RegInit(false.B)

    /** The two registers and the units between them: the read that places the operands (and waits for the writers the
      * network cannot answer), the assembly of the uop it hands to IS/EX, and the completion that frees the slot.
      */
    def issueStage(): Unit =
      val read = OperandRead.instantiate(OperandReadParameter(parameter.isa, parameter.defaultPc, parameter.bypass))
      val isu = Isu.instantiate(IsuParameter(parameter.isa, parameter.defaultPc))
      read.io.clock := io.clock
      read.io.resetN := io.resetN
      isu.io.clock := io.clock
      isu.io.resetN := io.resetN

      // The read: the payload's indexes out to the file and the network, the answers back in, and the placement of the
      // three operand slots, with the pace this read can move at.
      read.io.decoded :<= idis.bits
      read.io.slot_valid := isex.valid
      read.io.slot_rd_index := isex.bits.rd_index
      read.io.wb_valid := retire
      read.io.wb_rd := wbRd
      read.io.rs1_data := arf.io.rs1.data
      read.io.rs2_data := arf.io.rs2.data
      arf.io.rs1.index := read.io.rs1_index
      arf.io.rs2.index := read.io.rs2_index

      // The bypass bus: the read's indexes out to the network, its answers back into the read's select, and the
      // retiring write over the same bus (driven in the write-back stage, where it exists).
      io.bypass.rs1_index := read.io.rs1_index
      io.bypass.rs2_index := read.io.rs2_index
      read.io.bypass_rs1 :<= io.bypass.rs1
      read.io.bypass_rs2 :<= io.bypass.rs2

      // The issued uop: the read's operands and feasibility, the payload's held fields, and the CSR read.
      isu.io.decoded :<= idis.bits
      idis.ready := isu.io.decoded_ready
      isu.io.src_a := read.io.src_a
      isu.io.src_b := read.io.src_b
      isu.io.src_c := read.io.src_c
      isu.io.operands_ready := read.io.ready
      isu.io.slot :<= isex.bits
      isu.io.slot_valid := isex.valid
      isu.io.slot_first := slotFirst
      isu.io.complete := complete
      isu.io.flush := flushing
      isu.io.csr_rdata := csr.io.read_data
      csr.io.read_addr := isex.bits.csr_addr
      io.issue :<= isu.io.issue

      // The read's uop into IS/EX: handed over in the cycle ID/IS is consumed, taken when the units are done with the
      // previous one — the cycle they report it complete.
      feed.valid := idis.fire
      feed.bits :<= isu.io.moved
      isex.ready := complete
      slotFirst := feed.fire

    /** The write-back cycle: the completion latched with the destination it wrote, then the register write, the CSR
      * write, the commit and the redirect, all off that one register.
      */
    def retireStage(): Unit =
      when(complete) {
        wb :<= io.complete.bits
        wbRd := isex.bits.rd_index
      }
      wbValid := complete

      io.flush := flushing
      io.redirect.valid := flushing
      // The restart is wherever the branch actually resolved to: `next_pc`, not the target — under a predictor a
      // mispredict on a not-taken resolution points back at the fall-through.
      io.redirect.bits := wb.next_pc

      arf.io.write.valid := retire
      arf.io.write.bits.index := wbRd
      arf.io.write.bits.data := wb.data

      // The retiring write rides the bus back to the network as its older raw producer.
      io.bypass.commit.valid := retire
      io.bypass.commit.bits.rd_index := wbRd
      io.bypass.commit.bits.data := wb.data

      csr.io.write.valid := retire
      csr.io.write.bits.csr_type := wb.csr_type
      csr.io.write.bits.data := wb.csr_data

      io.commit.valid := retire
      io.commit.bits.next_pc := wb.next_pc
      io.commit.bits.rd_index := wbRd
      io.commit.bits.rd_value := wb.data
      io.commit.bits.is_mmio := wb.is_mmio
      io.commit.bits.csr_type := wb.csr_type
      io.commit.bits.csr_data := wb.csr_data

    /** Counters the simulator reads back: declared here, where the commit and the cycle are both known, and emitted
      * from this one call — a dump reads the registered set back out after elaboration. The stage counters are what the
      * commit count cannot say: how many cycles the pipeline spends holding — a payload waiting for its own operands, a
      * slot stuck on a multi-cycle unit — and how many a flush drops.
      */
    def counters(): Unit =
      val cycle = parameter.stats.stat("cycle", Regions.Throughput, "core clock cycles")
      val commit = parameter.stats.stat("inst_commit", Regions.Throughput, "retired instructions")
      val wait = parameter.stats.stat("operand_wait", Regions.Throughput, "cycles a payload waits for its own operands")
      val busy =
        parameter.stats.stat("slot_busy", Regions.Throughput, "cycles the execute slot holds an unfinished uop")
      val flush = parameter.stats.stat("flush", Regions.Throughput, "cycles a flush drains the younger stages")
      cycle.inc(true.B)
      commit.inc(retire)
      wait.inc(idis.valid & !idis.ready & (!isex.valid | complete) & !flushing)
      busy.inc(isex.valid & !complete)
      flush.inc(flushing)
      parameter.stats.rule(
        "ipc",
        s"${commit.name} / ${cycle.name}",
        Regions.Throughput,
        "",
        4,
        "instructions per cycle"
      )

    issueStage()
    retireStage()
    if parameter.flow.counters then counters()
