package nzea_core.back.mem

import chisel3._
import nzea_core.isa.LsuOp
import chisel3.util.{Cat, Mux1H, Valid}
import nzea_rtl.LiteBusRW
import nzea_rtl.PipeIO
import nzea_core.back.PrfWriteBundle
import nzea_core.back.FuCompletion
import nzea_core.back.MemRequest

/** Dbus user payload: tag + lsuOp + addr2 + waddr, passthrough req->resp for load PRF write. */
class DbusUserBundle(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val lsuOp = UInt(LsuOp.getWidth.W)
  val addr2 = UInt(2.W)
  val waddr = UInt(waddrWidth.W)
  val is_mmio = Bool()
}

/** MemUnit: receives mem_req from LSQ, pass-through to dbus (same-cycle, no timing change). Handles resp: completion
  * (is_done), WBU (load only). No unaligned support.
  */
class MemUnit(
    width: Int,
    tagWidth: Int,
    waddrWidth: Int,
    mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty
) extends Module {
  private val userPayloadWidth = tagWidth + LsuOp.getWidth + 2 + waddrWidth + 1 // +1 for is_mmio
  private val idWidth = 1
  private val userWidth = width.max(userPayloadWidth)
  private val dbusType = new LiteBusRW(width, width, userWidth, idWidth)
  private val userBundleType = new DbusUserBundle(tagWidth, waddrWidth)

  val io = IO(new Bundle {
    val mem_req = Flipped(Valid(new MemRequest(tagWidth, waddrWidth)))
    val mem_req_ready = Output(Bool())
    val completion = Output(Valid(new FuCompletion(tagWidth)))
    val dbus = dbusType.cloneType
    val out = new PipeIO(new PrfWriteBundle(waddrWidth))
  })

  val req = io.mem_req.bits
  val isStore = LsuOp.isStore(req.lsuOp)

  // Detect MMIO from injected device ranges (tile/platform layer provides these).
  private val addr = req.addr

  private val reqIsMmio: Bool =
    if (mmioRanges.isEmpty) false.B
    else {
      mmioRanges
        .map { case (base, size) =>
          addr >= base.U && addr < (base + size).U
        }
        .reduce(_ || _)
    }

  val userReq = Wire(userBundleType)
  userReq.tag := req.tag
  userReq.lsuOp := req.lsuOp.asUInt
  userReq.addr2 := req.addr(1, 0)
  userReq.waddr := req.waddr
  userReq.is_mmio := reqIsMmio

  io.mem_req_ready := io.dbus.req.ready
  io.dbus.req.valid := io.mem_req.valid
  io.dbus.req.bits.addr := Cat(req.addr(31, 2), 0.U(2.W))
  io.dbus.req.bits.wdata := req.wdata
  io.dbus.req.bits.wen := isStore
  io.dbus.req.bits.wstrb := req.wstrb
  io.dbus.req.bits.user := userReq.asUInt
  io.dbus.req.bits.id := 0.U

  io.dbus.resp.ready := !io.out.flush

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

  io.completion <> FuCompletion.report(respFire, respUser.tag, is_done = true.B, is_mmio = respUser.is_mmio)(
    tagWidth
  )

  io.out.valid := respFire && isLoadFromResp
  io.out.bits.waddr := respUser.waddr
  io.out.bits.data := loadData
}
