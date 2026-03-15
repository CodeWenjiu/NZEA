package nzea_core.retire

import chisel3._
import chisel3.util.{Cat, Decoupled, Mux1H, Valid}
import nzea_core.backend.LsuOp
import nzea_core.CoreBusReadWrite
import nzea_core.frontend.PrfWriteBundle
import nzea_core.PipeIO
import nzea_core.retire.rob.{RobMemReq, RobMemResp}

/** Dbus user payload: rob_id + lsuOp + addr2 + p_rd, passthrough req->resp for load PRF write. */
class DbusUserBundle(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id  = UInt(robIdWidth.W)
  val lsuOp   = UInt(LsuOp.getWidth.W)
  val addr2   = UInt(2.W)
  val p_rd    = UInt(prfAddrWidth.W)
}

/** MemUnit: LsBuffer inside; AGU enqueues; Rob issues; load data formatting; PRF write for loads.
  * No unaligned support: read always 4-byte aligned, extract byte/half from rdata; assume no unaligned instrs. */
class MemUnit(width: Int, robIdWidth: Int, lsBufferDepth: Int, prfAddrWidth: Int) extends Module {
  private val userPayloadWidth = robIdWidth + LsuOp.getWidth + 2 + prfAddrWidth
  private val userWidth = width.max(userPayloadWidth)
  private val dbusType = new CoreBusReadWrite(width, width, userWidth)

  private val userBundleType = new DbusUserBundle(robIdWidth, prfAddrWidth)

  val io = IO(new Bundle {
    val ls_enq      = Flipped(new PipeIO(new RobMemReq(robIdWidth, prfAddrWidth)))
    val issue       = Input(Bool())
    val issue_rob_id = Output(Valid(UInt(robIdWidth.W)))
    val flush       = Input(Bool())
    val resp        = Decoupled(new RobMemResp(robIdWidth))
    val dbus        = dbusType.cloneType
    val out   = new PipeIO(new PrfWriteBundle(prfAddrWidth))
  })

  // LS buffer: FIFO for mem ops, flush clears instantly
  private val ptrWidth = chisel3.util.log2Ceil(lsBufferDepth.max(2)) + 1
  val ls_slots   = Reg(Vec(lsBufferDepth, new RobMemReq(robIdWidth, prfAddrWidth)))
  val ls_head_ptr = RegInit(0.U(ptrWidth.W))
  val ls_tail_ptr = RegInit(0.U(ptrWidth.W))
  val ls_head_phys = ls_head_ptr(ptrWidth - 2, 0)
  val ls_tail_phys = ls_tail_ptr(ptrWidth - 2, 0)
  val ls_empty = ls_head_ptr === ls_tail_ptr
  val ls_full  = (ls_tail_phys === ls_head_phys) && (ls_tail_ptr(ptrWidth - 1) =/= ls_head_ptr(ptrWidth - 1))

  io.ls_enq.ready := !ls_full && !io.flush
  io.ls_enq.flush := io.flush
  io.issue_rob_id.valid := !ls_empty
  io.issue_rob_id.bits := ls_slots(ls_head_phys).rob_id

  val do_issue = io.issue && !ls_empty
  val head = ls_slots(ls_head_phys)
  val isStore = LsuOp.isStore(head.lsuOp)

  io.dbus.req.valid := do_issue
  io.dbus.req.bits.addr := Cat(head.addr(31, 2), 0.U(2.W))
  io.dbus.req.bits.wdata := head.wdata
  io.dbus.req.bits.wen := isStore
  io.dbus.req.bits.wstrb := head.wstrb
  val userReq = Wire(userBundleType)
  userReq.rob_id := head.rob_id
  userReq.lsuOp := head.lsuOp.asUInt
  userReq.addr2 := head.addr(1, 0)
  userReq.p_rd := head.p_rd
  io.dbus.req.bits.user := userReq.asUInt

  when(io.flush) {
    ls_head_ptr := 0.U
    ls_tail_ptr := 0.U
  }.otherwise {
    when(io.ls_enq.fire) {
      ls_slots(ls_tail_phys) := io.ls_enq.bits
      ls_tail_ptr := (ls_tail_ptr + 1.U)(ptrWidth - 1, 0)
    }
    when(do_issue && io.dbus.req.ready) {
      ls_head_ptr := (ls_head_ptr + 1.U)(ptrWidth - 1, 0)
    }
  }
  io.dbus.resp.ready := io.resp.ready
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

  io.resp.valid := respFire
  io.resp.bits.rob_id := respUser.rob_id
  io.resp.bits.data := Mux(isStoreFromResp, 0.U(32.W), loadData)

  io.out.valid := respFire && isLoadFromResp && respUser.p_rd =/= 0.U && !io.out.flush
  io.out.bits.addr := respUser.p_rd
  io.out.bits.data := loadData
}
