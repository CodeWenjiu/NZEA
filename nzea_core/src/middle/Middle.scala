package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, Valid, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BypassConfig, CoreConfig}
import nzea_core.back.{BackOut, IssueSlot, IssueUop}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Backend, CsrType, Gpr, Isa, Operand, Srcs}
import nzea_core.stats.{CoreStats, ThroughputCounters, ThroughputCountersParameter}
import nzea_rtl.{RtlFlow, StatsRegs}
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
  val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))
  val report = Flipped(new BackOut(width))
  val commit = Aligned(ValidIO(new CommitMsg(width)))
  val flush = Aligned(Bool())

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: the register read, the slot the backend works on, the commit point and the CSR file.
  *
  * An instruction crosses two slots here. Decode's payload lands in the first one and waits — it carries no operand
  * values — and the second one is the uop the backend works on, operands resolved. The read happens between them, in
  * the cycle the payload moves: that is the cycle the instruction below it completes, when its result is on the way to
  * the file, so a slot that names that instruction's register is answered by the file's own write and never needs a
  * fix-up. A payload whose operands are already settled moves in the cycle that instruction completes, which keeps the
  * pipeline at an instruction per cycle; one that reads what it writes moves a cycle later, which is the price of the
  * read being correct at the first attempt.
  *
  * The backend latches its result in an exit register and reports it a cycle later, so the middle retires one `done`
  * pulse per instruction and holds the uop stable until that pulse or a flush.
  *
  * The flush is sourced here: the backend reports the branch resolution and the middle broadcasts it, so every stage
  * reads one net instead of asking the stage below. A flush empties both slots and silences the issue channel — the
  * instruction behind a flush is on the path being killed.
  */
@generator
object Middle extends Generator[MiddleParameter, MiddleLayers, MiddleIO, MiddleProbe]:
  override def moduleName(parameter: MiddleParameter): String = "Middle"

  def architecture(parameter: MiddleParameter) =
    val io = summon[Interface[MiddleIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val indexWidth = Gpr.indexWidth

    // The decode slot: the instruction the front handed over, waiting for its operands. The execute slot: the uop the
    // backend works on. Its destination and CSR address ride beside it — the backend's uop carries neither.
    val decValid = RegInit(false.B)
    val dec = Reg(new IduOut(parameter.config))
    val exValid = RegInit(false.B)
    val ex = Reg(new IssueUop(parameter.config))
    val exRd = Reg(UInt(indexWidth))
    val exCsrAddr = Reg(UInt(12))
    // The destination the last `complete` retired: what the commit is about to write, and what the file is written with.
    val wbRd = Reg(UInt(indexWidth))

    val csr = CsrFile.instantiate(CsrFileParameter(width))
    csr.io.clock := io.clock
    csr.io.resetN := io.resetN

    val arf = Arf.instantiate(ArfParameter(width, parameter.bypass.writeback))
    arf.io.clock := io.clock
    arf.io.resetN := io.resetN
    arf.io.rs1.index := dec.rs1_index
    arf.io.rs2.index := dec.rs2_index

    // The read: the file answers both ports, and the operand form places those answers — with the immediate, the pc and
    // zero — into the three slots the backend reads.
    val (srcA, srcB, srcC) = Srcs.resolve(
      dec.srcs,
      Seq[(Operand, Referable[UInt])](
        Operand.Rs1 -> arf.io.rs1.data,
        Operand.Rs2 -> arf.io.rs2.data,
        Operand.Imm -> dec.imm,
        Operand.Pc -> dec.pc,
        Operand.Zero -> 0.U(width)
      ),
      width
    )
    // The same form once more, for the registers each slot was read from: 0 for the slots no register feeds, which is
    // also the index no instruction writes.
    val (idxA, idxB, idxC) = Srcs.resolve(
      dec.srcs,
      Seq[(Operand, Referable[UInt])](
        Operand.Rs1 -> dec.rs1_index,
        Operand.Rs2 -> dec.rs2_index,
        Operand.Imm -> 0.U(indexWidth),
        Operand.Pc -> 0.U(indexWidth),
        Operand.Zero -> 0.U(indexWidth)
      ),
      indexWidth
    )
    def reads(rd: Referable[UInt]): Referable[Bool] =
      (rd =/= 0.U(indexWidth)) & ((idxA === rd) | (idxB === rd) | (idxC === rd))

    val complete = io.report.complete
    val done = io.report.done
    val flushing = io.report.flush

    // The payload leaves when the execute slot frees up (its uop completes, or the slot is empty) and its operands are
    // settled. The instruction in execute is the producer a read cannot see yet — it writes its register in the cycle it
    // commits — so a payload naming that register waits for the completion, and without the writeback link one more
    // cycle for the file to hold the value.
    val waitsForExecute = exValid & reads(exRd)
    val waitsForCommit = done & reads(wbRd)
    val operandsReady =
      if parameter.bypass.writeback then !waitsForExecute
      else !waitsForExecute & !waitsForCommit
    val move = decValid & (!exValid | complete) & operandsReady & !flushing

    // The front's handover: the decode slot takes the instruction whenever its own contents are leaving (or it is
    // empty), so the payload behind a moving one lands in the same cycle — the read only ever waits for a producer.
    val take = io.decoded.fire
    io.decoded.ready := (!decValid | move) & !flushing
    when(take) { dec :<= io.decoded.bits }
    when(flushing) { decValid := false.B }.otherwise {
      when(move) { decValid := false.B }
      when(take) { decValid := true.B }
    }

    val moved = Wire(new IssueUop(parameter.config))
    moved.pc := dec.pc
    moved.imm := dec.imm
    moved.src_a := srcA
    moved.src_b := srcB
    moved.src_c := srcC
    moved.backend := dec.backend
    moved.op := dec.op
    // Both CSR fields are answered on the issue port, from the address riding beside the uop, so the register holds none
    // of them.
    moved.csr_type := 0.U(CsrType.width)
    moved.csr_rdata := 0.U(width)

    when(move) {
      ex :<= moved
      exRd := dec.rd_index
      exCsrAddr := dec.csr_addr
    }
    when(complete) { wbRd := exRd }
    when(flushing | (complete & !move)) { exValid := false.B }
    when(move) { exValid := true.B }

    // The uop the backend works on. It holds for as long as its instruction does, and `first` marks the cycle its
    // operands became valid, so a unit can take the level and still see where the uop began. A flush silences the
    // channel — the uop in the slot is on the path being killed, and the units take a vanished uop as abandoned.
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
    uop.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), CsrType.fromAddr(exCsrAddr))
    uop.csr_rdata := csr.io.read_data

    csr.io.read_addr := exCsrAddr
    io.issue.valid := exValid & !flushing
    io.issue.first := regNext(move, false.B) & !flushing
    io.issue.bits :<= uop

    io.flush := flushing
    io.redirect.valid := flushing
    io.redirect.bits := io.report.redirect_target

    arf.io.write.valid := done
    arf.io.write.bits.index := wbRd
    arf.io.write.bits.data := io.report.data

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
