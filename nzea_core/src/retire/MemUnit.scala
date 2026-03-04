package nzea_core.retire

import chisel3._
import chisel3.util.{Cat, Decoupled, Mux1H}
import nzea_core.backend.LsuOp
import nzea_core.CoreBusReadWrite
import nzea_core.retire.rob.{RobMemReq, RobMemResp}

/** Dbus user payload: rob_id + lsuOp + addr2, passthrough req->resp for load result selection. */
class DbusUserBundle(robIdWidth: Int) extends Bundle {
  val rob_id  = UInt(robIdWidth.W)
  val lsuOp   = UInt(LsuOp.getWidth.W)
  val addr2   = UInt(2.W)
}

class MemUnit(dbusType: CoreBusReadWrite, robIdWidth: Int) extends Module {
  private val userBundleType = new DbusUserBundle(robIdWidth)
  private val userPayloadWidth = robIdWidth + LsuOp.getWidth + 2
  require(
    dbusType.userWidth >= userPayloadWidth,
    s"userWidth ${dbusType.userWidth} < $userPayloadWidth"
  )

  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new RobMemReq(robIdWidth)))
    val resp = Decoupled(new RobMemResp(robIdWidth))
    val dbus = dbusType.cloneType
  })

  val isStore =
    io.req.bits.lsuOp === LsuOp.SB || io.req.bits.lsuOp === LsuOp.SH || io.req.bits.lsuOp === LsuOp.SW
  val isLoad = !isStore

  val pending = RegInit(false.B)
  when(io.dbus.req.fire) { pending := true.B }
  when(io.dbus.resp.fire) { pending := false.B }

  val dbusReqValid = io.req.valid && !pending
  io.dbus.req.valid := dbusReqValid
  io.dbus.req.bits.addr := io.req.bits.addr
  io.dbus.req.bits.wdata := io.req.bits.wdata
  io.dbus.req.bits.wen := isStore
  io.dbus.req.bits.wstrb := io.req.bits.wstrb
  val userReq = Wire(userBundleType)
  userReq.rob_id := io.req.bits.rob_id
  userReq.lsuOp := io.req.bits.lsuOp.asUInt
  userReq.addr2 := io.req.bits.addr(1, 0)
  io.dbus.req.bits.user := userReq.asUInt

  io.req.ready := io.dbus.req.ready && !pending
  io.dbus.resp.ready := pending
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

  val isStoreFromResp = respUser.lsuOp === LsuOp.SB.asUInt || respUser.lsuOp === LsuOp.SH.asUInt || respUser.lsuOp === LsuOp.SW.asUInt
  val complete = pending && io.dbus.resp.valid

  io.resp.valid := complete
  io.resp.bits.rob_id := respUser.rob_id
  io.resp.bits.data := Mux(isStoreFromResp, 0.U(32.W), loadData)
}
