package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, Valid, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.CoreConfig
import nzea_core.back.{BackOut, IssueUop}
import nzea_core.front.idu.{ArfWrite, IduOut}
import nzea_core.isa.{Backend, CsrType}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class MiddleParameter(isa: String, defaultPc: Long, sim: Boolean) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc, sim)

given upickle.default.ReadWriter[MiddleParameter] = upickle.default.macroRW

class MiddleLayers(parameter: MiddleParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class MiddleIO(parameter: MiddleParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val decoded = Flipped(Decoupled(new IduOut(width)))
  val redirect = Aligned(Valid(UInt(width)))
  val arf_write = Aligned(ValidIO(new ArfWrite(width)))
  val issue = Aligned(ValidIO(new IssueUop(width)))
  val report = Flipped(new BackOut(width))
  val commit = Aligned(ValidIO(new CommitMsg(width)))
  val flush = Aligned(Bool())

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: single-flight dispatch, the commit point, and the CSR file.
  *
  * Single-flight means the previous instruction has already committed when a new one issues, so the decode-stage
  * register read saw post-commit values — no hazard tracking and no forwarding. The backend latches its result in an
  * exit register and reports it a cycle later, so the middle retires one `done` pulse per instruction and holds the uop
  * (and the instruction it came from) stable until that pulse or a flush arrives.
  *
  * The flush is sourced here: the backend reports the branch resolution and the middle broadcasts it, so every stage
  * reads one net instead of asking the stage below.
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
    val ex = Reg(new IduOut(width))

    val csr = CsrFile.instantiate(CsrFileParameter(width))
    csr.io.clock := io.clock
    csr.io.resetN := io.resetN
    csr.io.read_addr := ex.csr_addr

    val uop = Wire(new IssueUop(width))
    uop.pc := ex.pc
    uop.imm := ex.imm
    uop.rs1 := ex.rs1_val
    uop.rs2 := ex.rs2_val
    uop.backend := ex.backend
    uop.op := ex.op
    // A memory instruction's `inst[31:20]` is an address offset, not a CSR address, so it maps to none.
    val isMem = ex.backend === Backend.MEM.lit.U(Backend.width)
    uop.csr_type := isMem ? (CsrType.None.lit.U(CsrType.width), CsrType.fromAddr(ex.csr_addr))
    uop.csr_rdata := csr.io.read_data

    io.issue.valid := exValid
    io.issue.bits :<= uop

    val issue = io.decoded.fire
    val done = io.report.done
    val flushing = io.report.flush

    when(issue) { ex :<= io.decoded.bits }

    // One instruction in flight: nothing new is admitted until the previous one commits or is flushed.
    io.decoded.ready := !exValid & !done & !flushing
    when(flushing | done) { exValid := false.B }.otherwise {
      when(issue) { exValid := true.B }
    }

    io.flush := flushing
    io.redirect.valid := flushing
    io.redirect.bits := io.report.redirect_target

    io.arf_write.valid := done
    io.arf_write.bits.rd_index := ex.rd_index
    io.arf_write.bits.data := io.report.data

    csr.io.write.valid := done
    csr.io.write.bits.csr_type := io.report.csr_type
    csr.io.write.bits.data := io.report.csr_data

    io.commit.valid := done
    io.commit.bits.next_pc := io.report.next_pc
    io.commit.bits.rd_index := ex.rd_index
    io.commit.bits.rd_value := io.report.data
    io.commit.bits.is_mmio := io.report.is_mmio
    io.commit.bits.csr_type := io.report.csr_type
    io.commit.bits.csr_data := io.report.csr_data
