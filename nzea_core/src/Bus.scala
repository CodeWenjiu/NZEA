package nzea_core

import chisel3._
import chisel3.util.Decoupled

/** Request payload for read-write bus: addr, wdata, wen (read when false). */
class CoreReq(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr  = UInt(addrWidth.W)
  val wdata = UInt(dataWidth.W)
  val wen   = Bool()
}

/** Shared: resp channel. Concrete bundles add req (address-only or CoreReq). */
trait CoreBusLike { self: Bundle =>
  def addrWidth: Int
  def dataWidth: Int
  val resp = Flipped(Decoupled(UInt(dataWidth.W)))
}

/** Read-only: req is just address. */
class CoreBusReadOnly(val addrWidth: Int, val dataWidth: Int) extends Bundle with CoreBusLike {
  val req = Decoupled(UInt(addrWidth.W))
}

/** Read-write: req is CoreReq(addr, wdata, wen). */
class CoreBusReadWrite(a: Int, d: Int) extends Bundle with CoreBusLike {
  override val addrWidth = a
  override val dataWidth = d
  val req = Decoupled(new CoreReq(addrWidth, dataWidth))
}

/** Parameterized by hasWrite: yields CoreBusReadOnly or CoreBusReadWrite. */
object CoreBus {
  def apply(addrWidth: Int, dataWidth: Int, hasWrite: Boolean): Bundle with CoreBusLike =
    if (hasWrite) new CoreBusReadWrite(addrWidth, dataWidth) else new CoreBusReadOnly(addrWidth, dataWidth)
}

class IFUOut(width: Int) extends Bundle {
  val pc   = UInt(width.W)
  val inst = UInt(32.W)
}

/** IDU decode result: pc, imm, GPR read data. */
class IDUOut(width: Int) extends Bundle {
  val pc  = UInt(width.W)
  val imm = UInt(32.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
}
