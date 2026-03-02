package nzea_core.backend

import chisel3._
import chisel3.util.{Cat, Decoupled, Fill, Mux1H}
import nzea_core.backend.fu.LsuOp
import nzea_core.CoreBusReadWrite

/** MemUnit: receives req (addr, wdata, wstrb, lsuOp, pred_next_pc, rob_id); when done outputs resp Decoupled(rob_id, data). */
class MemUnit(dbusType: CoreBusReadWrite, robIdWidth: Int) extends Module {
  private val userWidth = dbusType.userWidth

  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new RobMemReq(robIdWidth)))
    val resp = Decoupled(new RobMemResp(robIdWidth))
    val dbus = dbusType.cloneType
  })

  val reqReg    = Reg(new RobMemReq(robIdWidth))
  val busy      = RegInit(false.B)
  val loadPending = RegInit(false.B)
  val loadAddr2 = Reg(UInt(2.W))
  val loadLsuOp = Reg(LsuOp())

  val isLoad  = reqReg.lsuOp === LsuOp.LB || reqReg.lsuOp === LsuOp.LH || reqReg.lsuOp === LsuOp.LW ||
                reqReg.lsuOp === LsuOp.LBU || reqReg.lsuOp === LsuOp.LHU
  val isStore = reqReg.lsuOp === LsuOp.SB || reqReg.lsuOp === LsuOp.SH || reqReg.lsuOp === LsuOp.SW

  when(io.req.fire) {
    reqReg := io.req.bits
    busy   := true.B
    loadPending := false.B
  }

  val store_complete = busy && isStore && io.dbus.req.ready
  when(busy && isLoad) {
    when(!loadPending && io.dbus.req.ready) {
      loadPending := true.B
      loadAddr2   := reqReg.addr(1, 0)
      loadLsuOp   := reqReg.lsuOp
    }
  }
  val load_complete = busy && isLoad && loadPending && io.dbus.resp.valid
  when(io.resp.fire) {
    busy := false.B
    when(isLoad) { loadPending := false.B }
  }

  val dbusReqValid = busy && (isStore || (isLoad && !loadPending))
  io.dbus.req.valid := dbusReqValid
  io.dbus.req.bits.addr   := reqReg.addr
  io.dbus.req.bits.wdata  := reqReg.wdata
  io.dbus.req.bits.wen    := isStore
  io.dbus.req.bits.wstrb  := reqReg.wstrb
  io.dbus.req.bits.user   := reqReg.pred_next_pc

  val rdata = io.dbus.resp.bits.data
  val byteSel = Mux(loadAddr2 === 0.U, rdata(7, 0), Mux(loadAddr2 === 1.U, rdata(15, 8), Mux(loadAddr2 === 2.U, rdata(23, 16), rdata(31, 24))))
  val halfSel = Mux(loadAddr2(1), rdata(31, 16), rdata(15, 0))
  val lb  = Cat(Fill(24, byteSel(7)), byteSel)
  val lh  = Cat(Fill(16, halfSel(15)), halfSel)
  val lw  = rdata
  val lbu = Cat(0.U(24.W), byteSel)
  val lhu = Cat(0.U(16.W), halfSel)
  val loadData = Mux1H(loadLsuOp.asUInt, Seq(lb, lh, lw, lbu, lhu, 0.U(32.W), 0.U(32.W), 0.U(32.W)))

  io.dbus.resp.ready := loadPending

  io.resp.valid := store_complete || load_complete
  io.resp.bits.rob_id := reqReg.rob_id
  io.resp.bits.data   := Mux(isLoad, loadData, 0.U(32.W))

  io.req.ready := !busy
}
