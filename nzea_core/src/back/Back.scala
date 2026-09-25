package nzea_core.back

import chisel3._
import nzea_core.{BackBusPort, CoreChannels}
import nzea_core.back.int.IntPath
import nzea_core.back.mem.MemPath
import nzea_core.isa.{CsrType, FuOpWidth, FuSrcWidth, FuType}
import nzea_config.core.CoreConfig

/** Completion report from the backend to the in-order middle — the single reverse channel (back → middle). */
class BackOut(width: Int) extends Bundle {
  val done = Bool()
  val data = UInt(32.W)
  val next_pc = UInt(width.W)
  val csr_type = CsrType()
  val csr_data = UInt(32.W)
  val is_mmio = Bool()
  val flush = Bool()
  val redirect_target = UInt(width.W)
}

/** Ready-operand uop: all operands are values resolved by the middle (rs1/rs2 from the ARF, csr_rdata from the CSR
  * port), so the backend never sees register indexes. Held stable by the middle until done/flush.
  */
class IssueUop(width: Int)(implicit config: CoreConfig) extends Bundle {
  val pc = UInt(width.W)
  val imm = UInt(32.W)
  val fu_type = FuType()
  val fu_op = UInt(FuOpWidth.Width.W)
  val fu_src = UInt(FuSrcWidth.Width.W)
  val rd_index = UInt(5.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
  val csr_type = CsrType()
  val csr_rdata = UInt(32.W)
}

/** Backend merging point: routes the issue uop to its execution path (int/mem), broadcasts the branch flush, and
  * latches the single completion into the exit register. Dispatch itself lives in the paths — the comparison here is
  * only the factory's "which path owns this FuType" test, so a scheduler can grow inside a path untouched.
  */
class Back(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module {
  private val width = config.width

  val io = IO(new Bundle {
    val bus = new BackBusPort(width)
    val dbus = CoreChannels.dbus(width)
  })

  private val uopValid = io.bus.issue.valid
  private val u = io.bus.issue.bits

  private val int = Module(new IntPath(width))
  private val mem = Module(new MemPath(width, mmioRanges))

  private def owns(fuTypes: Seq[FuType.Type]): Bool = fuTypes.map(t => u.fu_type === t).reduce(_ || _)
  private val isInt = owns(ExecPath.intFuTypes)
  private val isMem = owns(ExecPath.memFuTypes)

  int.io.path.issue.valid := uopValid && isInt
  int.io.path.issue.bits := u
  mem.io.path.issue.valid := uopValid && isMem
  mem.io.path.issue.bits := u
  io.dbus <> mem.io.dbus

  private val flushing = int.io.path.redirect.valid
  int.io.path.flush := flushing
  mem.io.path.flush := flushing

  private val done = int.io.path.done || mem.io.path.done

  // Edge, not level: ALU/SYSU hold their completion for as long as the uop is held, and doneReg must pulse once.
  private val doneEdge = done && !RegNext(done, false.B)
  private val resultReg = Reg(new BackResult(width))
  when(doneEdge) {
    resultReg := Mux(isMem, mem.io.path.result, int.io.path.result)
  }
  private val doneReg = RegNext(doneEdge, false.B)

  io.bus.report.done := doneReg
  io.bus.report.data := resultReg.data
  io.bus.report.next_pc := resultReg.next_pc
  io.bus.report.csr_type := resultReg.csr_type
  io.bus.report.csr_data := resultReg.csr_data
  io.bus.report.is_mmio := resultReg.is_mmio
  io.bus.report.flush := flushing
  io.bus.report.redirect_target := int.io.path.redirect.bits
}