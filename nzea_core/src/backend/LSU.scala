package nzea_core.backend

import chisel3._
import chisel3.util.Valid
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.{LsAllocIO, LsWriteReq, RobEntryStateUpdate}
import nzea_rtl.{CoreBusReadWrite, PipeIO}

/** LSU wrapper: unifies AGU->LSQ write path, LSQ queueing/issue, and MemUnit bus/resp handling.
  * Internal behavior is unchanged; this module only consolidates boundaries.
  */
class LSU(width: Int, robIdWidth: Int, lsBufferDepth: Int, prfAddrWidth: Int) extends Module {
  private val lsqIdWidth = chisel3.util.log2Ceil(lsBufferDepth.max(2))
  private val userPayloadWidth = robIdWidth + nzea_core.backend.integer.LsuOp.getWidth + 2 + prfAddrWidth
  private val userWidth = width.max(userPayloadWidth)

  val io = IO(new Bundle {
    val ls_alloc = new LsAllocIO(robIdWidth, prfAddrWidth, lsqIdWidth)
    val agu_ls_write = Flipped(new PipeIO(new LsWriteReq(lsqIdWidth)))

    /** From ROB: allow LSQ head request issue and flush LSU state. */
    val issue = Input(Bool())
    val flush = Input(Bool())

    /** Back to ROB: current LSQ head rob_id and MemUnit completion update. */
    val issue_rob_id = Output(Valid(UInt(robIdWidth.W)))
    val rob_access   = Output(Valid(new RobEntryStateUpdate(robIdWidth)))

    /** To write-back path: load responses only. */
    val out  = new PipeIO(new PrfWriteBundle(prfAddrWidth))
    val dbus = new CoreBusReadWrite(width, width, userWidth)
  })

  private val lsq = Module(new LSQ(robIdWidth, lsBufferDepth, prfAddrWidth))
  private val mem = Module(new MemUnit(width, robIdWidth, prfAddrWidth))

  lsq.io.ls_alloc <> io.ls_alloc
  lsq.io.ls_write <> io.agu_ls_write
  lsq.io.issue    := io.issue
  lsq.io.flush    := io.flush

  io.issue_rob_id := lsq.io.issue_rob_id

  mem.io.mem_req.valid := lsq.io.mem_req.valid
  mem.io.mem_req.bits  := lsq.io.mem_req.bits
  lsq.io.mem_req_ready := mem.io.mem_req_ready

  io.rob_access <> mem.io.rob_access
  io.out        <> mem.io.out
  io.dbus       <> mem.io.dbus
}
