package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, Valid, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.RtlFlow
import nzea_config.core.{BypassConfig, CoreConfig, Isa}
import nzea_core.back.{BackOut, IssueSlot, IssueUop}
import nzea_core.front.idu.{ArfWrite, ExecForward, IduOut}
import nzea_core.isa.{Backend, CsrType, Gpr}
import nzea_core.stats.{CoreStats, ThroughputCounters, ThroughputCountersParameter}
import nzea_rtl.StatsRegs
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class MiddleParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, bypass: BypassConfig) extends Parameter:
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
  val arf_write = Aligned(ValidIO(new ArfWrite(width)))
  val forward = Aligned(ValidIO(new ExecForward(width)))
  val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))
  val report = Flipped(new BackOut(width))
  val commit = Aligned(ValidIO(new CommitMsg(width)))
  val flush = Aligned(Bool())

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: a two-slot pipeline (execute, writeback), the commit point, and the CSR file.
  *
  * One instruction is in execute — the uop the backend is working on — and one is in writeback, the destination the
  * backend finished a cycle earlier. Splitting the slots is what lets the execute slot refill the cycle a result lands
  * instead of staying busy until the commit; whether that refill is *safe* is the configuration's answer, because a
  * read only sees the result early through a bypass link. With an execute link the window is the cycle the backend
  * produces the result, without one it is the commit cycle (the write the commit makes is what the read sees), and with
  * no link at all it is the cycle after.
  *
  * The backend latches its result in an exit register and reports it a cycle later, so the middle retires one `done`
  * pulse per instruction and holds the uop (and the instruction it came from) stable until that pulse or a flush.
  *
  * The flush is sourced here: the backend reports the branch resolution and the middle broadcasts it, so every stage
  * reads one net instead of asking the stage below. A flush empties the execute slot and silences the issue channel —
  * the instruction behind a flush is on the path being killed.
  */
@generator
object Middle extends Generator[MiddleParameter, MiddleLayers, MiddleIO, MiddleProbe]:
  override def moduleName(parameter: MiddleParameter): String = "Middle"

  def architecture(parameter: MiddleParameter) =
    val io = summon[Interface[MiddleIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width

    val exValid = RegInit(false.B)
    val ex = Reg(new IduOut(parameter.config))
    // Writeback slot: the destination the backend finished a cycle ago, which is what the commit needs from it. The
    // rest of the commit comes from the report (value, CSR write, next pc), so this is the whole of the slot.
    val wbRd = Reg(UInt(Gpr.indexWidth))

    val csr = CsrFile.instantiate(CsrFileParameter(width))
    csr.io.clock := io.clock
    csr.io.resetN := io.resetN
    csr.io.read_addr := ex.csr_addr

    val uop = Wire(new IssueUop(parameter.config))
    uop.pc := ex.pc
    uop.imm := ex.imm
    uop.src_a := ex.src_a
    uop.src_b := ex.src_b
    uop.src_c := ex.src_c
    uop.backend := ex.backend
    uop.op := ex.op
    // A memory instruction's `inst[31:20]` is an address offset, not a CSR address, so it maps to none.
    val isMem = ex.backend === Backend.MEM.lit.U(Backend.width)
    uop.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), CsrType.fromAddr(ex.csr_addr))
    uop.csr_rdata := csr.io.read_data

    val issue = io.decoded.fire
    val complete = io.report.complete
    val done = io.report.done
    val flushing = io.report.flush

    when(issue) { ex :<= io.decoded.bits }
    when(complete) { wbRd := ex.rd_index }

    // A commit cycle is an admission window only when the configuration lets the read see the write that commit makes
    // in the same cycle (`BypassConfig`); without the link the read waits for the write to land. An execute link opens
    // the window one cycle earlier, in the cycle the backend produces the result, and hands it to the read directly. A
    // flush closes the window either way — the instruction the front would offer is on the path being killed.
    val admit =
      if parameter.bypass.execute then !exValid | complete
      else if parameter.bypass.writeback then !exValid | done
      else !exValid & !done
    io.decoded.ready := admit & !flushing
    when(flushing) { exValid := false.B }.otherwise {
      when(issue) { exValid := true.B }
        .otherwise { when(complete) { exValid := false.B } }
    }

    // An instruction admitted in its predecessor's commit cycle leaves `valid` high across the boundary, so the slot
    // carries the arrival itself: `first` marks the new uop's first cycle. A flush silences the channel — the uop in
    // execute is on the path being killed, and the units take a vanished uop as an abandoned completion.
    io.issue.valid := exValid & !flushing
    io.issue.first := regNext(issue, false.B) & !flushing
    io.issue.bits :<= uop

    // The execute stage's value on its way to a read that takes it early: `complete` marks the cycle it is there, and
    // the slot names what it is — the index is the one the instruction in execute writes, 0 when it writes nothing,
    // which no read matches.
    io.forward.valid := complete
    io.forward.bits.rd_index := ex.rd_index
    io.forward.bits.data := io.report.exec_data

    io.flush := flushing
    io.redirect.valid := flushing
    io.redirect.bits := io.report.redirect_target

    io.arf_write.valid := done
    io.arf_write.bits.rd_index := wbRd
    io.arf_write.bits.data := io.report.data

    csr.io.write.valid := done
    csr.io.write.bits.csr_type := io.report.csr_type
    csr.io.write.bits.data := io.report.csr_data

    io.commit.valid := done
    io.commit.bits.next_pc := io.report.next_pc
    io.commit.bits.rd_index := wbRd
    io.commit.bits.rd_value := io.report.data
    io.commit.bits.is_mmio := io.report.is_mmio
    io.commit.bits.csr_type := io.report.csr_type
    io.commit.bits.csr_data := io.report.csr_data

    // Counters the simulator reads back: the retirement rate is counted here because this is where cycles and commits
    // are both known. The box they live in is emitted and declared by `nzea_rtl.StatsRegs` / `nzea_core.stats.CoreStats`.
    if parameter.flow.counters then
      val stats = ThroughputCounters.instantiate(ThroughputCountersParameter())
      stats.io.clock := io.clock
      stats.io.resetN := io.resetN.asBool
      stats.io.stat_cycle.en := true.B
      stats.io.stat_cycle.data :=
        addWrap(stats.io.stat_cycle.value, 1.U(StatsRegs.Width), StatsRegs.Width)
      stats.io.stat_inst_commit.en := done
      stats.io.stat_inst_commit.data :=
        addWrap(stats.io.stat_inst_commit.value, 1.U(StatsRegs.Width), StatsRegs.Width)
