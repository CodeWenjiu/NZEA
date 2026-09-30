package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.back.{IssueSlot, IssueUop}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Backend, CsrType, Isa}
import nzea_core.middle.ExecSlot
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IsuParameter(isa: Isa, defaultPc: Long) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IsuParameter] = upickle.default.macroRW

class IsuLayers(parameter: IsuParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IsuIO(parameter: IsuParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())

  /** From the middle's ID/IS register: the decoded payload, in the cycle the register presents it. */
  val decoded = Flipped(new IduOut(parameter.config))

  /** The read's pace back to that register: set when this cycle's read can place the payload's operands. */
  val decoded_ready = Aligned(Bool())

  /** From the read: the operands it placed, and whether every one of them is settled. */
  val src_a = Flipped(UInt(width))
  val src_b = Flipped(UInt(width))
  val src_c = Flipped(UInt(width))
  val operands_ready = Flipped(Bool())

  /** The CSR file's read side, addressed from the execute slot's held address. */
  val csr_rdata = Flipped(UInt(width))

  /** The execute slot's pace: a completion frees it, and a flush kills the cycle. */
  val complete = Flipped(Bool())
  val flush = Flipped(Bool())

  /** The execute slot as the middle's IS/EX register presents it: the payload the units work on, its validity, and the
    * pulse marking the cycle it arrived.
    */
  val slot = Flipped(new ExecSlot(parameter.config))
  val slot_valid = Flipped(Bool())
  val slot_first = Flipped(Bool())

  /** To the IS/EX register: the uop the read produced, with the destination and CSR address riding in it. */
  val moved = Aligned(new ExecSlot(parameter.config))

  /** To the backend: the uop as a level, roles carried by `valid` and `first`. */
  val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))

class IsuProbe(parameter: IsuParameter) extends DVBundle[IsuParameter, IsuLayers](parameter)

/** Issue unit: the assembly of the uop the units work on, between the middle's two registers.
  *
  * Combinational: the ID/IS and IS/EX registers are the middle's (`Isu` neither owns nor instantiates one), and this
  * unit is what sits between them — the same rule the front-end follows around [[nzea_core.front.idu.Idu]]. The
  * operands arrive already placed from [[nzea_core.middle.isu.OperandRead]], together with the read's feasibility; this
  * unit assembles the `moved` payload, carrying the fetch's predicted next pc through untouched, and presents the uop
  * to the backend, silencing it when the slot is empty or the flush is killing the cycle. The slot holds the uop for
  * the unit's whole residence — the units take a level, not a handshake — and `slot_first` marks the cycle it arrived.
  */
@generator
object Isu extends Generator[IsuParameter, IsuLayers, IsuIO, IsuProbe]:
  override def moduleName(parameter: IsuParameter): String = "Isu"

  def architecture(parameter: IsuParameter) =
    val io = summon[Interface[IsuIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    /** ID/IS is consumed when the read has placed the payload's operands — the read owns that test, including the
      * in-flight writers it had to wait for — and the execute stage is about to free up: its uop completes, or the slot
      * is empty. A flush moves nothing: what waits in the stages is on the path being killed.
      */
    def canPlaceOperands(): Referable[Bool] =
      (!io.slot_valid | io.complete) & io.operands_ready & !io.flush

    /** What moves into IS/EX: the read's operands, and the payload's held fields with the destination and the CSR
      * address riding in it.
      */
    def moveUop(): Unit =
      val dec = io.decoded
      io.moved.pc := dec.pc
      io.moved.next_pc := dec.next_pc
      io.moved.fall_through := dec.fall_through
      io.moved.imm := dec.imm
      io.moved.src_a := io.src_a
      io.moved.src_b := io.src_b
      io.moved.src_c := io.src_c
      io.moved.backend := dec.backend
      io.moved.op := dec.op
      io.moved.rd_index := dec.rd_index
      io.moved.csr_addr := dec.csr_addr

    /** The uop the units work on, as the slot's register holds it: the payload reversed into the backend's contract,
      * with the CSR read answered here. A memory instruction's `inst[31:20]` is an address offset, not a CSR address,
      * so it maps to none, and a flush silences the channel — the units take a vanished uop as abandoned.
      */
    def issue(): Unit =
      val slot = io.slot
      val uop = Wire(new IssueUop(parameter.config))
      uop.pc := slot.pc
      uop.next_pc := slot.next_pc
      uop.fall_through := slot.fall_through
      uop.imm := slot.imm
      uop.src_a := slot.src_a
      uop.src_b := slot.src_b
      uop.src_c := slot.src_c
      uop.backend := slot.backend
      uop.op := slot.op
      uop.rd_index := slot.rd_index
      val isMem = slot.backend === Backend.MEM.lit.U(Backend.width)
      uop.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), CsrType.fromAddr(slot.csr_addr))
      uop.csr_rdata := io.csr_rdata
      io.issue.valid := io.slot_valid
      io.issue.first := io.slot_first & !io.flush
      io.issue.bits :<= uop

    io.decoded_ready := canPlaceOperands()
    moveUop()
    issue()
