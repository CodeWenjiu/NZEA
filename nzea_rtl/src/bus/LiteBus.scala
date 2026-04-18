package nzea_rtl

import chisel3._

/** Request payload for read-write bus: addr, wdata, wen, wstrb, user (passthrough to resp). */
class LiteReq(addrWidth: Int, dataWidth: Int, userWidth: Int = 0) extends Bundle {
  val addr  = UInt(addrWidth.W)
  val wdata = UInt(dataWidth.W)
  val wen   = Bool()
  val wstrb = UInt((dataWidth / 8).W)
  val user  = UInt(userWidth.W)
}

/** Response payload: data and user (user echoed from req). */
class LiteResp(dataWidth: Int, userWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val user = UInt(userWidth.W)
}

/** Shared: resp channel. Concrete bundles add req. */
trait LiteBusLike { self: Bundle =>
  def addrWidth: Int
  def dataWidth: Int
  def userWidth: Int
}

/** Read-only: req is addr + user; resp returns data + user (user passthrough). PipeIO for flush propagation.
  * resp.flush (Output from producer) carries flush to bridge; no separate flush needed. */
class LiteBusRO(val addrWidth: Int, val dataWidth: Int, val userWidth: Int = 0) extends Bundle with LiteBusLike {
  val req  = new PipeIO(new Bundle {
    val addr = UInt(addrWidth.W)
    val user = UInt(userWidth.W)
  })
  val resp = Flipped(new PipeIO(new LiteResp(dataWidth, userWidth)))
}

/** Read-write: req is LiteReq; resp returns data + user (user passthrough from req). PipeIO for flush propagation.
  * resp.flush (Output from producer) carries flush to bridge; no separate flush needed. */
class LiteBusRW(a: Int, d: Int, val userWidth: Int = 0) extends Bundle with LiteBusLike {
  override val addrWidth = a
  override val dataWidth = d
  val req  = new PipeIO(new LiteReq(addrWidth, dataWidth, userWidth))
  val resp = Flipped(new PipeIO(new LiteResp(dataWidth, userWidth)))
}

/** One-stage request register slice for LiteBusRO.
  * Used to cut long request-side timing paths while keeping response latency unchanged.
  */
class LiteBusROReqRegisterSlice(
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int = 0
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRO(addrWidth, dataWidth, userWidth))
    val out = new LiteBusRO(addrWidth, dataWidth, userWidth)
  })

  PipelineConnect(io.in.req, io.out.req)

  io.in.resp.valid := io.out.resp.valid
  io.in.resp.bits := io.out.resp.bits
  io.out.resp.ready := io.in.resp.ready
  io.out.resp.flush := io.in.resp.flush
}

/** Parameterized by hasWrite: yields LiteBusRO or LiteBusRW. */
object LiteBus {
  def apply(addrWidth: Int, dataWidth: Int, hasWrite: Boolean, userWidth: Int = 0): Bundle with LiteBusLike =
    if (hasWrite) new LiteBusRW(addrWidth, dataWidth, userWidth) else new LiteBusRO(addrWidth, dataWidth)
}
