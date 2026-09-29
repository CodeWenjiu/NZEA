package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BypassConfig, CoreConfig}
import nzea_core.back.{IssueSlot, IssueUop}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Backend, CsrType, Gpr, Isa, Operand, Srcs}
import nzea_core.middle.ExecSlot
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IsuParameter(isa: Isa, defaultPc: Long, bypass: BypassConfig) extends Parameter:
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

  /** The register file's read side: the indexes the payload names, the values the file answers with. */
  val rs1_index = Aligned(UInt(Gpr.indexWidth))
  val rs2_index = Aligned(UInt(Gpr.indexWidth))
  val rs1_data = Flipped(UInt(width))
  val rs2_data = Flipped(UInt(width))

  /** The CSR file's read side, addressed from the execute slot's held address. */
  val csr_rdata = Flipped(UInt(width))

  /** Write-back: its destination settles the operand hazard, and a completion frees the execute slot. */
  val wb_valid = Flipped(Bool())
  val wb_rd = Flipped(UInt(Gpr.indexWidth))
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

/** Issue unit: the middle's read — the register file's answers placed into the uop's slots, and the uop handed to the
  * execute slot.
  *
  * Combinational: the ID/IS and IS/EX registers are the middle's (`Isu` neither owns nor instantiates one), and this
  * unit is what sits between them — the same rule the front-end follows around [[nzea_core.front.idu.Idu]]. The read
  * happens in the cycle a payload moves out of ID/IS: that is the cycle the instruction below it completes, when its
  * result is on the way to the file, so a slot that names that instruction's register is answered by the file's own
  * write and never needs a fix-up. A payload whose operands are already settled moves in the cycle that instruction
  * completes, which keeps the pipeline at an instruction per cycle; one that reads what it writes moves a cycle later,
  * which is the price of the read being correct at the first attempt. The slot holds the uop for the unit's whole
  * residence — the units take a level, not a handshake — and `slot_first` marks the cycle it arrived.
  *
  * The files themselves stay in [[nzea_core.middle.Middle]]: their reads are this unit's, their writes are
  * write-back's, so the indexes and the read data cross this boundary rather than the storage.
  */
@generator
object Isu extends Generator[IsuParameter, IsuLayers, IsuIO, IsuProbe]:
  override def moduleName(parameter: IsuParameter): String = "Isu"

  def architecture(parameter: IsuParameter) =
    val io = summon[Interface[IsuIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val indexWidth = Gpr.indexWidth

    /** The read: the file answers the two indexes the payload names, and the operand form places those answers — with
      * the immediate, the pc and zero — into the three slots the units read.
      */
    def readOperands(): (Referable[UInt], Referable[UInt], Referable[UInt]) =
      val dec = io.decoded
      io.rs1_index := dec.rs1_index
      io.rs2_index := dec.rs2_index
      Srcs.resolve(
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

    /** ID/IS is consumed when this cycle's read can place the payload's operands: the execute stage is about to free up
      * (its uop completes, or the slot is empty) and the operands are settled. The instruction in execute is the
      * producer a read cannot see yet — it writes its register in the cycle it commits — so a payload naming that
      * register waits for the completion, and without the writeback link one more cycle for the file to hold the value.
      * A flush moves nothing: what waits in the stages is on the path being killed.
      */
    def canPlaceOperands(): Referable[Bool] =
      // The operand form once more, for the registers each slot was read from: 0 for the slots no register feeds,
      // which is also the index no instruction writes.
      val (idxA, idxB, idxC) = Srcs.resolve(
        io.decoded.srcs,
        Seq[(Operand, Referable[UInt])](
          Operand.Rs1 -> io.decoded.rs1_index,
          Operand.Rs2 -> io.decoded.rs2_index,
          Operand.Imm -> 0.U(indexWidth),
          Operand.Pc -> 0.U(indexWidth),
          Operand.Zero -> 0.U(indexWidth)
        ),
        indexWidth
      )
      def reads(rd: Referable[UInt]): Referable[Bool] =
        (rd =/= 0.U(indexWidth)) & ((idxA === rd) | (idxB === rd) | (idxC === rd))
      val waitsForExecute = io.slot_valid & reads(io.slot.rd_index)
      val waitsForCommit = io.wb_valid & reads(io.wb_rd)
      val operandsReady =
        if parameter.bypass.writeback then !waitsForExecute
        else !waitsForExecute & !waitsForCommit
      (!io.slot_valid | io.complete) & operandsReady & !io.flush

    /** What moves into IS/EX: the read's uop, with the destination and the CSR address riding in it. The fall-through
      * is resolved here, where the pc is a held value: the execute cycle has the branch target adder in it already, and
      * this value does not depend on anything computed there.
      */
    def moveUop(a: Referable[UInt], b: Referable[UInt], c: Referable[UInt]): Unit =
      val dec = io.decoded
      io.moved.pc := dec.pc
      io.moved.pc_plus4 := prefixAdder(dec.pc, 4.U(width), false.B, width)
      io.moved.imm := dec.imm
      io.moved.src_a := a
      io.moved.src_b := b
      io.moved.src_c := c
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
      uop.pc_plus4 := slot.pc_plus4
      uop.imm := slot.imm
      uop.src_a := slot.src_a
      uop.src_b := slot.src_b
      uop.src_c := slot.src_c
      uop.backend := slot.backend
      uop.op := slot.op
      val isMem = slot.backend === Backend.MEM.lit.U(Backend.width)
      uop.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), CsrType.fromAddr(slot.csr_addr))
      uop.csr_rdata := io.csr_rdata
      io.issue.valid := io.slot_valid
      io.issue.first := io.slot_first & !io.flush
      io.issue.bits :<= uop

    val (srcA, srcB, srcC) = readOperands()
    io.decoded_ready := canPlaceOperands()
    moveUop(srcA, srcB, srcC)
    issue()
