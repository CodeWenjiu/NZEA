package nzea_core.middle

import chisel3._
import nzea_core.MiddleBusPort
import nzea_core.back.IssueUop
import nzea_core.front.IduOut
import nzea_core.front.{CsrFile, CsrType, FuType}
import nzea_config.core.CoreConfig

/** In-order middle engine: single-flight dispatch, commit point, CSR file.
  *
  * Single-flight means the previous instruction has already committed when a new one issues, so the front-end capture
  * read the ARF against post-commit values — no hazard and no forwarding. The backend latches the execution result in
  * its exit register and reports it one pulse later (report.done); the middle retires that pulse directly (ARF
  * write-back, CSR write, commit_msg) without a result register of its own. The polymorphic engine point: an OoO
  * engine replaces this module without touching the front-end or backend contracts.
  */
class Middle(implicit config: CoreConfig) extends Module {
  private val width = config.width

  val io = IO(new Bundle {
    val bus = new MiddleBusPort(width)
  })

  val exValid = RegInit(false.B)
  val exBits = Reg(new IduOut(width))
  val issue = io.bus.decoded.valid && io.bus.decoded.ready
  when(issue) { exBits := io.bus.decoded.bits }

  private val csr = Module(new CsrFile)
  csr.io.read_addr := exBits.csr_addr

  val uop = Wire(new IssueUop(width))
  uop.pc := exBits.pc
  uop.imm := exBits.imm
  uop.fu_type := exBits.fu_type
  uop.fu_op := exBits.fu_op
  uop.fu_src := exBits.fu_src
  uop.rd_index := exBits.rd_index
  uop.rs1 := exBits.rs1_val
  uop.rs2 := exBits.rs2_val
  uop.csr_type := Mux(exBits.fu_type === FuType.LSU, CsrType.None, CsrType.fromAddr(exBits.csr_addr))
  uop.csr_rdata := csr.io.read_data

  io.bus.issue.valid := exValid
  io.bus.issue.bits := uop

  val done = io.bus.report.done
  val flushing = io.bus.report.flush
  io.bus.decoded.flush := flushing
  io.bus.redirect.valid := flushing
  io.bus.redirect.bits := io.bus.report.redirect_target

  when(flushing || done) { exValid := false.B }.elsewhen(issue) { exValid := true.B }

  // report.done is a one-cycle pulse (the backend exit latch), so the commit bus is driven directly by it; the commit
  // cycle blocks issue, keeping exBits on the retiring instruction while its data comes from the stable exit register.
  io.bus.decoded.ready := !exValid && !io.bus.report.done

  io.bus.arf_write.valid := io.bus.report.done
  io.bus.arf_write.bits.rd_index := exBits.rd_index
  io.bus.arf_write.bits.data := io.bus.report.data

  csr.io.csr_write.valid := io.bus.report.done
  csr.io.csr_write.bits.csr_type := io.bus.report.csr_type
  csr.io.csr_write.bits.data := io.bus.report.csr_data

  io.bus.commit.valid := io.bus.report.done
  io.bus.commit.bits.next_pc := io.bus.report.next_pc
  io.bus.commit.bits.rd_index := exBits.rd_index
  io.bus.commit.bits.rd_value := io.bus.report.data
  io.bus.commit.bits.is_mmio := io.bus.report.is_mmio
  io.bus.commit.bits.csr_type := io.bus.report.csr_type
  io.bus.commit.bits.csr_data := io.bus.report.csr_data
}