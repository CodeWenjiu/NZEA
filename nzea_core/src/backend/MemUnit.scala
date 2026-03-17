package nzea_core.backend

import chisel3._
import chisel3.util.{Cat, Decoupled, Mux1H, Valid}
import nzea_core.CoreBusReadWrite
import nzea_core.frontend.PrfWriteBundle
import nzea_core.PipeIO
import nzea_core.retire.rob.{LsAllocIO, LsAllocReq, LsWriteReq, Rob, RobAccessIO}

/** Dbus user payload: rob_id + lsuOp + addr2 + p_rd, passthrough req->resp for load PRF write. */
class DbusUserBundle(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id  = UInt(robIdWidth.W)
  val lsuOp   = UInt(LsuOp.getWidth.W)
  val addr2   = UInt(2.W)
  val p_rd    = UInt(prfAddrWidth.W)
}

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

/** MemUnit: LS_Queue with ls_alloc at dispatch, ls_write from AGU. Issue only when head.data_ready.
  * No unaligned support: read always 4-byte aligned, extract byte/half from rdata. */
class MemUnit(width: Int, robIdWidth: Int, lsBufferDepth: Int, prfAddrWidth: Int) extends Module {
  private val lsqIdWidth = chisel3.util.log2Ceil(lsBufferDepth.max(2))
  private val userPayloadWidth = robIdWidth + LsuOp.getWidth + 2 + prfAddrWidth
  private val userWidth = width.max(userPayloadWidth)
  private val dbusType = new CoreBusReadWrite(width, width, userWidth)
  private val userBundleType = new DbusUserBundle(robIdWidth, prfAddrWidth)

  val io = IO(new Bundle {
    val ls_alloc      = new LsAllocIO(robIdWidth, prfAddrWidth, lsqIdWidth)
    val ls_write      = Flipped(new PipeIO(new LsWriteReq(lsqIdWidth)))
    val issue         = Input(Bool())
    val issue_rob_id  = Output(Valid(UInt(robIdWidth.W)))
    val rob_access    = new RobAccessIO(robIdWidth)
    val dbus          = dbusType.cloneType
    val out           = new PipeIO(new PrfWriteBundle(prfAddrWidth))
  })

  // LS_Queue: circular buffer, allocate at tail, issue from head
  private val ptrWidth = lsqIdWidth + 1
  val ls_slots = Reg(Vec(lsBufferDepth, new LsqSlot(robIdWidth, prfAddrWidth)))
  val ls_head_ptr = RegInit(0.U(ptrWidth.W))
  val ls_tail_ptr = RegInit(0.U(ptrWidth.W))
  val ls_head_phys = ls_head_ptr(lsqIdWidth - 1, 0)
  val ls_tail_phys = ls_tail_ptr(lsqIdWidth - 1, 0)
  val ls_empty = ls_head_ptr === ls_tail_ptr
  val ls_full  = (ls_tail_phys === ls_head_phys) && (ls_tail_ptr(lsqIdWidth) =/= ls_head_ptr(lsqIdWidth))

  io.ls_alloc.ready := !ls_full && !io.out.flush
  io.ls_alloc.lsq_id := ls_tail_phys
  io.ls_write.ready := true.B
  io.ls_write.flush := io.out.flush

  val head = ls_slots(ls_head_phys)
  val head_ready = head.valid && head.data_ready
  io.issue_rob_id.valid := !ls_empty && head_ready
  io.issue_rob_id.bits := head.rob_id

  val do_issue = io.issue && !ls_empty && head_ready
  val isStore = LsuOp.isStore(head.lsuOp)

  io.dbus.req.valid := do_issue
  io.dbus.req.bits.addr := Cat(head.addr(31, 2), 0.U(2.W))
  io.dbus.req.bits.wdata := head.wdata
  io.dbus.req.bits.wen := isStore
  io.dbus.req.bits.wstrb := head.wstrb
  val userReq = Wire(userBundleType)
  userReq.rob_id := head.rob_id
  userReq.lsuOp := head.lsuOp
  userReq.addr2 := head.addr(1, 0)
  userReq.p_rd := head.p_rd
  io.dbus.req.bits.user := userReq.asUInt

  when(io.out.flush) {
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
    when(do_issue && io.dbus.req.ready) {
      ls_slots(ls_head_phys).valid := false.B
      ls_slots(ls_head_phys).data_ready := false.B
      ls_head_ptr := (ls_head_ptr + 1.U)(ptrWidth - 1, 0)
    }
  }
  io.dbus.resp.ready := !io.out.flush
  io.dbus.resp.flush := false.B
  io.dbus.flush := false.B

  val rdata = io.dbus.resp.bits.data
  val respUser = io.dbus.resp.bits.user.asTypeOf(userBundleType)
  val byteSel = Mux(
    respUser.addr2 === 0.U,
    rdata(7, 0),
    Mux(
      respUser.addr2 === 1.U,
      rdata(15, 8),
      Mux(respUser.addr2 === 2.U, rdata(23, 16), rdata(31, 24))
    )
  )
  val halfSel = Mux(respUser.addr2(1), rdata(31, 16), rdata(15, 0))
  val lb = Cat(chisel3.util.Fill(24, byteSel(7)), byteSel)
  val lh = Cat(chisel3.util.Fill(16, halfSel(15)), halfSel)
  val lw = rdata
  val lbu = Cat(0.U(24.W), byteSel)
  val lhu = Cat(0.U(16.W), halfSel)
  val loadData =
    Mux1H(respUser.lsuOp, Seq(lb, lh, lw, lbu, lhu, 0.U(32.W), 0.U(32.W), 0.U(32.W)))

  val isStoreFromResp = LsuOp.isStore(respUser.lsuOp)
  val isLoadFromResp = LsuOp.isLoad(respUser.lsuOp)
  val respFire = io.dbus.resp.valid && io.dbus.resp.ready

  io.rob_access <> Rob.entryStateUpdate(respFire, respUser.rob_id, is_done = true.B)(robIdWidth)

  io.out.valid := respFire && isLoadFromResp && respUser.p_rd =/= 0.U && !io.out.flush
  io.out.bits.addr := respUser.p_rd
  io.out.bits.data := loadData
}
