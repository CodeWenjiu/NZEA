package nzea_core.backend

import chisel3._
import nzea_core.backend.integer.LsuOp
import chisel3.util.{Cat, Mux1H, Valid}
import nzea_core.CoreBusReadWrite
import nzea_core.frontend.PrfWriteBundle
import nzea_core.PipeIO
import nzea_core.retire.rob.{Rob, RobEntryStateUpdate, RobMemReq}

/** Dbus user payload: rob_id + lsuOp + addr2 + p_rd, passthrough req->resp for load PRF write. */
class DbusUserBundle(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id  = UInt(robIdWidth.W)
  val lsuOp   = UInt(LsuOp.getWidth.W)
  val addr2   = UInt(2.W)
  val p_rd    = UInt(prfAddrWidth.W)
}

/** MemUnit: receives mem_req from LSQ, pass-through to dbus (same-cycle, no timing change).
  * Handles resp: rob_access (is_done), WBU (load only). No unaligned support. */
class MemUnit(width: Int, robIdWidth: Int, prfAddrWidth: Int) extends Module {
  private val userPayloadWidth = robIdWidth + LsuOp.getWidth + 2 + prfAddrWidth
  private val userWidth = width.max(userPayloadWidth)
  private val dbusType = new CoreBusReadWrite(width, width, userWidth)
  private val userBundleType = new DbusUserBundle(robIdWidth, prfAddrWidth)

  val io = IO(new Bundle {
    val mem_req       = Flipped(Valid(new RobMemReq(robIdWidth, prfAddrWidth)))
    val mem_req_ready = Output(Bool())
    val rob_access    = Output(Valid(new RobEntryStateUpdate(robIdWidth)))
    val dbus          = dbusType.cloneType
    val out           = new PipeIO(new PrfWriteBundle(prfAddrWidth))
  })

  val req = io.mem_req.bits
  val isStore = LsuOp.isStore(req.lsuOp)
  val userReq = Wire(userBundleType)
  userReq.rob_id := req.rob_id
  userReq.lsuOp := req.lsuOp.asUInt
  userReq.addr2 := req.addr(1, 0)
  userReq.p_rd := req.p_rd

  io.mem_req_ready := io.dbus.req.ready
  io.dbus.req.valid := io.mem_req.valid
  io.dbus.req.bits.addr := Cat(req.addr(31, 2), 0.U(2.W))
  io.dbus.req.bits.wdata := req.wdata
  io.dbus.req.bits.wen := isStore
  io.dbus.req.bits.wstrb := req.wstrb
  io.dbus.req.bits.user := userReq.asUInt

  io.dbus.resp.ready := !io.out.flush
  io.dbus.resp.flush := io.out.flush

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

  val isLoadFromResp = LsuOp.isLoad(respUser.lsuOp)
  val respFire = io.dbus.resp.valid && io.dbus.resp.ready

  io.rob_access <> Rob.entryStateUpdate(respFire, respUser.rob_id, is_done = true.B)(robIdWidth)

  io.out.valid := respFire && isLoadFromResp
  io.out.bits.addr := respUser.p_rd
  io.out.bits.data := loadData
}
