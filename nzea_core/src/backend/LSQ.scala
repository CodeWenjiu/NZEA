package nzea_core.backend

import chisel3._
import nzea_core.backend.integer.LsuOp
import chisel3.util.{Mux1H, Valid}
import nzea_core.PipeIO
import nzea_core.retire.rob.{LsAllocIO, LsAllocReq, LsWriteReq, RobMemReq}

/** LS_Queue slot: alloc writes rob_id/p_rd/lsuOp; AGU writes addr/wdata/wstrb and sets data_ready. */
class LsqSlot(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val valid     = Bool()
  val data_ready = Bool()
  val rob_id    = UInt(robIdWidth.W)
  val p_rd      = UInt(prfAddrWidth.W)
  val lsuOp     = UInt(LsuOp.getWidth.W)
  val addr      = UInt(32.W)
  val wdata     = UInt(32.W)
  val wstrb     = UInt(4.W)
}

/** LSQ: circular buffer, ls_alloc at dispatch, ls_write from AGU. Issues mem_req when head.data_ready.
  * No logic change from original MemUnit LS_Queue; only structural separation. */
class LSQ(robIdWidth: Int, lsBufferDepth: Int, prfAddrWidth: Int) extends Module {
  private val lsqIdWidth = chisel3.util.log2Ceil(lsBufferDepth.max(2))

  val io = IO(new Bundle {
    val ls_alloc       = new LsAllocIO(robIdWidth, prfAddrWidth, lsqIdWidth)
    val ls_write       = Flipped(new PipeIO(new LsWriteReq(lsqIdWidth)))
    val issue          = Input(Bool())
    val issue_rob_id   = Output(Valid(UInt(robIdWidth.W)))
    val mem_req        = Output(Valid(new RobMemReq(robIdWidth, prfAddrWidth)))
    val mem_req_ready  = Input(Bool())
    val flush          = Input(Bool())
  })

  private val ptrWidth = lsqIdWidth + 1
  val ls_slots = Reg(Vec(lsBufferDepth, new LsqSlot(robIdWidth, prfAddrWidth)))
  val ls_head_ptr = RegInit(0.U(ptrWidth.W))
  val ls_tail_ptr = RegInit(0.U(ptrWidth.W))
  val ls_head_phys = ls_head_ptr(lsqIdWidth - 1, 0)
  val ls_tail_phys = ls_tail_ptr(lsqIdWidth - 1, 0)
  val ls_empty = ls_head_ptr === ls_tail_ptr
  val ls_full  = (ls_tail_phys === ls_head_phys) && (ls_tail_ptr(lsqIdWidth) =/= ls_head_ptr(lsqIdWidth))

  io.ls_alloc.ready := !ls_full
  io.ls_alloc.lsq_id := ls_tail_phys
  io.ls_write.ready := true.B
  io.ls_write.flush := io.flush

  val head = ls_slots(ls_head_phys)
  val head_ready = head.valid && head.data_ready
  io.issue_rob_id.valid := !ls_empty && head_ready
  io.issue_rob_id.bits := head.rob_id

  val do_issue = io.issue && !ls_empty && head_ready
  io.mem_req.valid := do_issue
  io.mem_req.bits.rob_id := head.rob_id
  io.mem_req.bits.addr := head.addr
  io.mem_req.bits.wdata := head.wdata
  io.mem_req.bits.wstrb := head.wstrb
  io.mem_req.bits.lsuOp := LsuOp.safe(head.lsuOp)._1
  io.mem_req.bits.p_rd := head.p_rd

  when(io.flush) {
    ls_head_ptr := 0.U
    ls_tail_ptr := 0.U
    for (i <- 0 until lsBufferDepth) {
      ls_slots(i).valid := false.B
      ls_slots(i).data_ready := false.B
    }
  }.otherwise {
    when(io.ls_alloc.valid && io.ls_alloc.ready) {
      ls_slots(ls_tail_phys).valid := true.B
      ls_slots(ls_tail_phys).data_ready := false.B
      ls_slots(ls_tail_phys).rob_id := io.ls_alloc.bits.rob_id
      ls_slots(ls_tail_phys).p_rd := io.ls_alloc.bits.p_rd
      ls_slots(ls_tail_phys).lsuOp := io.ls_alloc.bits.lsuOp
      ls_tail_ptr := (ls_tail_ptr + 1.U)(ptrWidth - 1, 0)
    }
    when(io.ls_write.fire) {
      val id = io.ls_write.bits.lsq_id
      ls_slots(id).addr := io.ls_write.bits.addr
      ls_slots(id).wdata := io.ls_write.bits.wdata
      ls_slots(id).wstrb := io.ls_write.bits.wstrb
      ls_slots(id).data_ready := true.B
    }
    when(do_issue && io.mem_req_ready) {
      ls_slots(ls_head_phys).valid := false.B
      ls_slots(ls_head_phys).data_ready := false.B
      ls_head_ptr := (ls_head_ptr + 1.U)(ptrWidth - 1, 0)
    }
  }
}
