package nzea_core.min.middle

import chisel3._
import nzea_core.min.MiddleBusPort
import nzea_core.min.back.MinIssueUop
import nzea_core.min.front.MinIduOut
import nzea_core.frontend.{CsrFile, CsrType, FuType}
import nzea_config.core.CoreConfig

/** In-order middle engine: single-flight dispatch, architectural register file, and the retire/commit point.
  *
  * // Issue captures the decoded instruction — operands arrive as values (the ARF lives in the front, see
  * // [[nzea_core.min.front.MinIdu]]) — single-flight means the previous instruction has already committed, so the
  * // front-end capture cycle did the read against post-commit values: no hazard and no forwarding. The instruction is
  * // wrapped as a ready-operand [[MinIssueUop]] and handed to the execute backend on `bus.issue`, which executes it
  * // and reports done/flush on `bus.report`. The backend latches the execution result in its stage-exit register and
  * // reports it one cycle later; the middle retires it on the following cycle (ARF write-back on `bus.arf_write`, CSR
  * // write, commit_msg on `bus.commit`) while the next instruction issues — the commit-cycle data comes from the
  * // backend's exit latch (stable register Q), so the middle holds no result register of its own. CSR writes only
  * // happen on that commit cycle, so squashed instructions never modify architectural state.
  *
  * This is the middle polymorphic point: an OoO engine (rename + PRF + ROB + issue queue) replaces this module later
  * without touching the front-end ([[MinFront]]) or the execute backend ([[MinExec]]) contracts. Under single-flight
  * there is no extra pipeline stage between the bus channels — the uop on `bus.issue` is a combinational packing of the
  * captured instruction and stays stable until done.
  */
class MinMiddle(implicit config: CoreConfig) extends Module {
  private val width = config.width

  val io = IO(new Bundle {
    val bus = new MiddleBusPort(width)
  })

  // -------- Single-flight entry --------

  val exValid = RegInit(false.B)
  val exBits = Reg(new MinIduOut(width))
  val issue = io.bus.decoded.valid && io.bus.decoded.ready
  when(issue) { exBits := io.bus.decoded.bits }

  private val csr = Module(new CsrFile)
  csr.io.read_addr := exBits.csr_addr // combinational read port, captured into the uop

  // -------- Ready-operand uop to the backend --------

  val uop = Wire(new MinIssueUop(width))
  uop.pc := exBits.pc
  uop.imm := exBits.imm
  uop.fu_type := exBits.fu_type
  uop.fu_op := exBits.fu_op
  uop.fu_src := exBits.fu_src
  uop.rd_index := exBits.rd_index
  uop.rs1 := exBits.rs1_val
  uop.rs2 := exBits.rs2_val
  // SYSU CSR operation type; LSU is forced to None (it never drives the SYSU port).
  uop.csr_type := Mux(exBits.fu_type === FuType.LSU, CsrType.None, CsrType.fromAddr(exBits.csr_addr))
  uop.csr_rdata := csr.io.read_data

  io.bus.issue.valid := exValid
  io.bus.issue.bits := uop

  // -------- Completion / flush --------

  val done = io.bus.report.done
  val flushing = io.bus.report.flush
  io.bus.decoded.flush := flushing
  io.bus.redirect.valid := flushing
  io.bus.redirect.bits := io.bus.report.redirect_target

  when(flushing || done) { exValid := false.B }.elsewhen(issue) { exValid := true.B }

  // -------- Retire (commit cycle, driven directly by the report pulse) --------

  // No separate wbValid register: report.done is already a one-cycle pulse (the backend exit latch), so the commit
  // bus (ARF write, CSR write, commit_msg) is driven combinationally by it — one register per instruction saved.
  // The commit cycle blocks issue (ready below), so exBits still holds the retiring instruction (its rd_index is the
  // retiring rd), and the commit data comes from the report channel, which by then carries the backend's exit latch
  // (stable register Q) — no combinational forward needed, the ARF write lands on the commit edge and the next
  // decode cycle reads the updated value.
  io.bus.decoded.ready := !exValid && !io.bus.report.done

  // -------- Commit (report.done cycle: ARF write-back, CSR write, external message) --------

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
  // No RAS on this engine: the commit-message ret field (shared with the OoO contract) stays inert.
  io.bus.commit.bits.is_ret.foreach(_ := false.B)
}
