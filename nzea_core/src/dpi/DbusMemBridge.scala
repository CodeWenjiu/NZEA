package nzea_core.dpi

import chisel3._
import chisel3.util._
import nzea_rtl._

/** Dbus bridge using SyncReadMem (no DPI). Same pipeline structure as DbusDpiBridge, for testing. */
class DbusMemBridge(addrWidth: Int, dataWidth: Int, userWidth: Int = 0) extends Module {

  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth, 1))
  })

  val flush = io.bus.resp.flush
  io.bus.req.flush := flush

  val req = io.bus.req.bits
  val isRead = !req.wen
  val respType = new LiteResp(dataWidth, userWidth, 1)

  val internalResp = Wire(new PipeIO(respType))
  val stage1 = Wire(new PipeIO(respType))
  val readRespOut = Wire(new PipeIO(respType))
  stage1.flush := flush
  readRespOut.flush := flush

  io.bus.req.ready := internalResp.ready
  val fire = io.bus.req.valid && io.bus.req.ready
  val readFire = fire && isRead
  val writeFire = fire && req.wen

  val mem = SyncReadMem(1 << 24, UInt(32.W))
  val rdata = mem.read(req.addr(23, 0))

  when(writeFire) {
    mem.write(req.addr(23, 0), req.wdata)
  }

  internalResp.valid := fire
  internalResp.bits.data := Mux(readFire, rdata, 0.U(dataWidth.W))
  internalResp.bits.user := req.user
  internalResp.bits.id := req.id

  PipelineConnect(internalResp, stage1)
  PipelineConnect(stage1, readRespOut)
  readRespOut.ready := io.bus.resp.ready

  io.bus.resp.valid := readRespOut.valid
  io.bus.resp.bits.data := readRespOut.bits.data
  io.bus.resp.bits.user := readRespOut.bits.user
  io.bus.resp.bits.id := readRespOut.bits.id
}
