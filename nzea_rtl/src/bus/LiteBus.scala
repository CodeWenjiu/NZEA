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

  // Response side: tiny flushable FIFO + registered dequeue output.
  // The FIFO stores entries; the dequeue is locked into `respDeqData` and held until
  // consumed, so `io.in.resp.bits` is register-driven. This cuts the combinational
  // FIFO-read fanout that previously reached the I-Cache refill path (STA critical:
  // 129-fanout net from respQ read to SetAssoc.bypassDataReg). Cost: +1 cycle response
  // latency, which LiteBus allows (no fixed-latency assumption).
  val respQ = Reg(Vec(2, new LiteResp(dataWidth, userWidth, idWidth)))
  val head = RegInit(0.U(1.W))
  val tail = RegInit(0.U(1.W))
  val count = RegInit(0.U(2.W))
  val flush = io.in.resp.flush

  val canEnq = count =/= 2.U
  val canDeq = count =/= 0.U

  // Dequeue output stage: prefetch the next FIFO entry on the same cycle the current
  // one is consumed, so back-to-back responses keep one-per-cycle throughput.
  val respDeqValid = RegInit(false.B)
  val respDeqData = RegInit(0.U.asTypeOf(new LiteResp(dataWidth, userWidth, idWidth)))

  io.out.resp.ready := canEnq && !flush
  io.out.resp.flush := flush

  io.in.resp.valid := respDeqValid && !flush
  io.in.resp.bits := respDeqData

  val enqFire = io.out.resp.valid && io.out.resp.ready
  val deqFire = io.in.resp.valid && io.in.resp.ready
  val deqTake = (canDeq && !respDeqValid) || (deqFire && canDeq && count > 1.U)

  when(flush) {
    head := 0.U
    tail := 0.U
    count := 0.U
    respDeqValid := false.B
  }.otherwise {
    when(enqFire) { respQ(tail) := io.out.resp.bits; tail := ~tail }
    when(deqTake) { respDeqData := respQ(head); head := ~head }
    when(deqFire) { respDeqValid := false.B }
    when(deqTake) { respDeqValid := true.B }
    count := MuxCase(
      count,
      Seq(
        (enqFire && !deqTake) -> (count + 1.U),
        (!enqFire && deqTake) -> (count - 1.U)
      )
    )
  }

}
