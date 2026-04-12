package nzea_rtl

import chisel3._

/** Request payload for read-write bus: addr, wdata, wen, wstrb, user (passthrough to resp). */
class CoreReq(addrWidth: Int, dataWidth: Int, userWidth: Int = 0) extends Bundle {
  val addr  = UInt(addrWidth.W)
  val wdata = UInt(dataWidth.W)
  val wen   = Bool()
  val wstrb = UInt((dataWidth / 8).W)
  val user  = UInt(userWidth.W)
}

/** Response payload: data and user (user echoed from req). */
class CoreResp(dataWidth: Int, userWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val user = UInt(userWidth.W)
}

/** Shared: resp channel. Concrete bundles add req. */
trait CoreBusLike { self: Bundle =>
  def addrWidth: Int
  def dataWidth: Int
  def userWidth: Int
}

/** Read-only: req is addr + user; resp returns data + user (user passthrough). PipeIO for flush propagation.
  * resp.flush (Output from Core) carries flush to bridge; no separate flush needed. */
class CoreBusReadOnly(val addrWidth: Int, val dataWidth: Int, val userWidth: Int = 0) extends Bundle with CoreBusLike {
  val req  = new PipeIO(new Bundle {
    val addr = UInt(addrWidth.W)
    val user = UInt(userWidth.W)
  })
  val resp = Flipped(new PipeIO(new CoreResp(dataWidth, userWidth)))
}

/** Read-write: req is CoreReq; resp returns data + user (user passthrough from req). PipeIO for flush propagation.
  * resp.flush (Output from Core) carries flush to bridge; no separate flush needed. */
class CoreBusReadWrite(a: Int, d: Int, val userWidth: Int = 0) extends Bundle with CoreBusLike {
  override val addrWidth = a
  override val dataWidth = d
  val req  = new PipeIO(new CoreReq(addrWidth, dataWidth, userWidth))
  val resp = Flipped(new PipeIO(new CoreResp(dataWidth, userWidth)))
}

/** Parameterized by hasWrite: yields CoreBusReadOnly or CoreBusReadWrite. */
object CoreBus {
  def apply(addrWidth: Int, dataWidth: Int, hasWrite: Boolean, userWidth: Int = 0): Bundle with CoreBusLike =
    if (hasWrite) new CoreBusReadWrite(addrWidth, dataWidth, userWidth) else new CoreBusReadOnly(addrWidth, dataWidth)
}
