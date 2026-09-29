package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, Valid, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BypassConfig, CoreConfig}
import nzea_core.back.{IssueSlot, IssueUop, PathResult}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Backend, CsrType, Gpr, Isa, Operand, Srcs}
import nzea_core.stats.Regions
import nzea_rtl.{RtlFlow, StatScope}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class MiddleParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, bypass: BypassConfig, stats: StatScope)
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
  val commit = Aligned(ValidIO(new CommitMsg(width)))
  val flush = Aligned(Bool())

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: the register read, the execute slot the units work on, and the write-back register — three stages,
  * the last of which is also where the commit, the CSR file and the flush are.
  *
  * The read and the execute slot are elastic, so [[pipelineConnect]] owns those two registers and this module drives
  * only their consumer side. The read happens in the cycle the payload moves out of ID/IS: that is the cycle the
  * instruction below it completes, when its result is on the way to the file, so a slot that names that instruction's
  * register is answered by the file's own write and never needs a fix-up. A payload whose operands are already settled
  * moves in the cycle that instruction completes, which keeps the pipeline at an instruction per cycle; one that reads
  * what it writes moves a cycle later, which is the price of the read being correct at the first attempt. IS/EX holds
  * the uop for the unit's whole residence — the units take a level, not a handshake — and `first` marks the cycle it
  * arrived.
  *
  * EX/WB is neither elastic nor cancellable: the completion lands in it, the register write, the CSR write and the
  * commit ride it, and a mispredicted branch in it is what cancels the younger stages. The flush is sourced here, from
  * that register, so every stage reads one net instead of asking the stage below.
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

    // The stage registers. ID/IS and IS/EX are elastic, so the two calls below own them and this module drives only
    // their consumer side. EX/WB is a plain register: its entry is the one that cancels others, so nothing cancels it.
    val idis = Wire(Decoupled(new IduOut(parameter.config)))
    val isex = Wire(Decoupled(new IssueUop(parameter.config)))
    val feed = Wire(Decoupled(new IssueUop(parameter.config)))
    pipelineConnect(io.decoded, idis, io.flush, "idis", FlushHandover.Refuse)
    pipelineConnect(feed, isex, io.flush, "isex", FlushHandover.Refuse)

    val wb = Reg(new PathResult(width))
    val wbValid = RegInit(false.B)
    // The destination and CSR address ride beside the uop in the execute slot: the backend's uop carries no register
    // index, and the CSR address is only needed while the uop is resident (the read is combinational during it).
    val exRd = Reg(UInt(indexWidth))
    val exCsrAddr = Reg(UInt(12))
    // The destination of the instruction that completed: what the write-back cycle writes the file with.
    val wbRd = Reg(UInt(indexWidth))
    val complete = io.complete.valid
    // The write-back cycle: what completed last cycle retires in this one.
    val retire = wbValid
    // A mispredicted branch in write-back cancels the younger stages, and its target steers the front.
    val flushing = retire & wb.redirect_valid

    val csr = CsrFile.instantiate(CsrFileParameter(width))
    csr.io.clock := io.clock
    csr.io.resetN := io.resetN

    val arf = Arf.instantiate(ArfParameter(width, parameter.bypass.writeback))
    arf.io.clock := io.clock
    arf.io.resetN := io.resetN
    // The payload in ID/IS, and the read it is waiting for: the file answers both ports, and the operand form places
    // those answers — with the immediate, the pc and zero — into the three slots the units read.
    val dec = idis.bits
    arf.io.rs1.index := dec.rs1_index
    arf.io.rs2.index := dec.rs2_index
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

    // ID/IS is consumed when the read can place its operands: the execute stage is about to free up (its uop completes,
    // or the slot is empty) and the payload's own operands are settled. The instruction in execute is the producer a
    // read cannot see yet — it writes its register in the cycle it commits — so a payload naming that register waits
    // for the completion, and without the writeback link one more cycle for the file to hold the value. A flush moves
    // nothing: what waits in the stages is on the path being killed.
    val waitsForExecute = isex.valid & reads(exRd)
    val waitsForCommit = retire & reads(wbRd)
    val operandsReady =
      if parameter.bypass.writeback then !waitsForExecute
      else !waitsForExecute & !waitsForCommit
    idis.ready := (!isex.valid | complete) & operandsReady & !flushing

    val moved = Wire(new IssueUop(parameter.config))
    moved.pc := dec.pc
    // The fall-through is resolved here, where the pc is a held value: the execute cycle has the branch target adder in
    // it already, and this value does not depend on anything computed there.
    moved.pc_plus4 := prefixAdder(dec.pc, 4.U(width), false.B, width)
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

    // The read hands its uop to IS/EX, which takes it when the units are done with the previous one — the cycle they
    // report it complete. `fill` is that move, and `first` below is its edge.
    feed.valid := idis.fire
    feed.bits :<= moved
    isex.ready := complete
    val fill = feed.valid & feed.ready
    when(fill) {
      exRd := dec.rd_index
      exCsrAddr := dec.csr_addr
    }
    // The completion lands in EX/WB, with the destination it wrote: what the commit and the file read next cycle.
    when(complete) {
      wb :<= io.complete.bits
      wbRd := exRd
    }
    wbValid := complete

    // The uop the units work on: a level held for as long as its instruction takes, with `first` marking the cycle its
    // operands became valid, so a unit can take the level and still see where the uop began. A flush silences the
    // channel — the uop in the slot is on the path being killed, and the units take a vanished uop as abandoned.
    val uop = Wire(new IssueUop(parameter.config))
    uop.pc := isex.bits.pc
    uop.pc_plus4 := isex.bits.pc_plus4
    uop.imm := isex.bits.imm
    uop.src_a := isex.bits.src_a
    uop.src_b := isex.bits.src_b
    uop.src_c := isex.bits.src_c
    uop.backend := isex.bits.backend
    uop.op := isex.bits.op
    // A memory instruction's `inst[31:20]` is an address offset, not a CSR address, so it maps to none.
    val isMem = isex.bits.backend === Backend.MEM.lit.U(Backend.width)
    uop.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), CsrType.fromAddr(exCsrAddr))
    uop.csr_rdata := csr.io.read_data

    csr.io.read_addr := exCsrAddr
    // `first` is the fill's edge, registered once: the uop's arrival cycle, which a unit takes a level apart from.
    val first = RegInit(false.B)
    first := fill
    io.issue.valid := isex.valid
    io.issue.first := first & !flushing
    io.issue.bits :<= uop

    // The write-back cycle: the register write, the CSR write, the commit and the redirect all ride the one register.
    io.flush := flushing
    io.redirect.valid := flushing
    io.redirect.bits := wb.redirect_target

    arf.io.write.valid := retire
    arf.io.write.bits.index := wbRd
    arf.io.write.bits.data := wb.data

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

    // Counters the simulator reads back: declared here, where the commit and the cycle are both known, and emitted
    // from this one call — a dump reads the registered set back out after elaboration.
    if parameter.flow.counters then
      val cycle = parameter.stats.stat("cycle", Regions.Throughput, "core clock cycles")
      val commit = parameter.stats.stat("inst_commit", Regions.Throughput, "retired instructions")
      cycle.inc(true.B)
      commit.inc(retire)
      parameter.stats.rule(
        "ipc",
        s"${commit.name} / ${cycle.name}",
        Regions.Throughput,
        "",
        4,
        "instructions per cycle"
      )
