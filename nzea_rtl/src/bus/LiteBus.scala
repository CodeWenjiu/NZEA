package nzea_rtl

import chisel3._
import chisel3.util.{log2Ceil, Cat, MuxCase}

/** Address window for bus decode. */
case class LiteAddrRange(base: BigInt, size: BigInt) {
  require(base >= 0, s"base must be >= 0, got $base")
  require(size > 0, s"size must be > 0, got $size")
  val endExclusive: BigInt = base + size
}

/** Request payload: addr, wdata, wen, wstrb, user, id. */
class LiteReq(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val wdata = UInt(dataWidth.W)
  val wen = Bool()
  val wstrb = UInt((dataWidth / 8).W)
  val user = UInt(userWidth.W)
  val id = UInt(idWidth.W)
}

/** Response payload: data, user, id. */
class LiteResp(dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val user = UInt(userWidth.W)
  val id = UInt(idWidth.W)
}

trait LiteBusLike { self: Bundle =>
  def addrWidth: Int
  def dataWidth: Int
  def userWidth: Int
  def idWidth: Int
}

/** Unified read-write bus with request ID for outstanding support. */
class LiteBusRW(
    val addrWidth: Int,
    val dataWidth: Int,
    val userWidth: Int,
    val idWidth: Int
) extends Bundle
    with LiteBusLike {
  require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
  require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
  val req = new PipeIO(new LiteReq(addrWidth, dataWidth, userWidth, idWidth))
  val resp = Flipped(new PipeIO(new LiteResp(dataWidth, userWidth, idWidth)))
}

/** One-stage register slice. Cuts combinational paths on both req and resp channels. */
class LiteBusRegisterSlice(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int
) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val out = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)
  })

  PipelineConnect(io.in.req, io.out.req)

  // Response side uses a tiny flushable FIFO to cut ready-path timing.
  val respQ = Reg(Vec(2, new LiteResp(dataWidth, userWidth, idWidth)))
  val head = RegInit(0.U(1.W))
  val tail = RegInit(0.U(1.W))
  val count = RegInit(0.U(2.W))
  val flush = io.in.resp.flush

  val canEnq = count =/= 2.U
  val canDeq = count =/= 0.U

  io.out.resp.ready := canEnq && !flush
  io.out.resp.flush := flush

  io.in.resp.valid := canDeq && !flush
  io.in.resp.bits := Mux(canDeq, respQ(head), 0.U.asTypeOf(new LiteResp(dataWidth, userWidth, idWidth)))

  val enqFire = io.out.resp.valid && io.out.resp.ready
  val deqFire = io.in.resp.valid && io.in.resp.ready

  when(flush) {
    head := 0.U
    tail := 0.U
    count := 0.U
  }.otherwise {
    when(enqFire) { respQ(tail) := io.out.resp.bits; tail := ~tail }
    when(deqFire) { head := ~head }
    count := MuxCase(
      count,
      Seq(
        (enqFire && !deqFire) -> (count + 1.U),
        (!enqFire && deqFire) -> (count - 1.U)
      )
    )
  }

}
