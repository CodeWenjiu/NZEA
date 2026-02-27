package nzea_core

import chisel3._
import chisel3.util.Decoupled

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

/** Read-only: req is just address. No user. */
class CoreBusReadOnly(val addrWidth: Int, val dataWidth: Int) extends Bundle with CoreBusLike {
  override def userWidth: Int = 0
  val req  = Decoupled(UInt(addrWidth.W))
  val resp = Flipped(Decoupled(UInt(dataWidth.W)))
}

/** Read-write: req is CoreReq; resp returns data + user (user passthrough from req). */
class CoreBusReadWrite(a: Int, d: Int, val userWidth: Int = 0) extends Bundle with CoreBusLike {
  override val addrWidth = a
  override val dataWidth = d
  val req  = Decoupled(new CoreReq(addrWidth, dataWidth, userWidth))
  val resp = Flipped(Decoupled(new CoreResp(dataWidth, userWidth)))
}

/** Parameterized by hasWrite: yields CoreBusReadOnly or CoreBusReadWrite. */
object CoreBus {
  def apply(addrWidth: Int, dataWidth: Int, hasWrite: Boolean, userWidth: Int = 0): Bundle with CoreBusLike =
    if (hasWrite) new CoreBusReadWrite(addrWidth, dataWidth, userWidth) else new CoreBusReadOnly(addrWidth, dataWidth)
}

