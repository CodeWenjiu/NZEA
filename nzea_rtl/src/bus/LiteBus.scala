package nzea_rtl

import chisel3._
import chisel3.util.{log2Ceil, Cat, MuxCase, RegEnable}
import chisel3.util.DecoupledIO

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

/** Unified read-write bus with request ID for outstanding support. The channels are plain Decoupled: cancellation is
  * a master-side contract (digest + tag/epoch matching) — the transport protocol carries no flush/rollback semantics.
  */
class LiteBusRW(
    val addrWidth: Int,
    val dataWidth: Int,
    val userWidth: Int,
    val idWidth: Int
) extends Bundle
    with LiteBusLike {
  require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
  require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
  val req = new DecoupledIO(new LiteReq(addrWidth, dataWidth, userWidth, idWidth))
  val resp = Flipped(new DecoupledIO(new LiteResp(dataWidth, userWidth, idWidth)))
}

/** Read-only request payload: address + user + id, no write fields. */
class LiteReqRO(addrWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val user = UInt(userWidth.W)
  val id = UInt(idWidth.W)
}

/** Read-only bus: request without the write channel (addr/user/id only). Used by fetch-side masters (I-Fetch, I-Cache)
  * so "never writes" is a type-level guarantee instead of a `wen = false.B` convention. Channels are plain Decoupled
  * (cancellation is master-side, see [[LiteBusRW]]).
  */
class LiteBusRO(
    val addrWidth: Int,
    val dataWidth: Int,
    val userWidth: Int,
    val idWidth: Int
) extends Bundle
    with LiteBusLike {
  require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
  require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
  val req = new DecoupledIO(new LiteReqRO(addrWidth, userWidth, idWidth))
  val resp = Flipped(new DecoupledIO(new LiteResp(dataWidth, userWidth, idWidth)))
}

/** Combinational adapter: read-only master side → full read/write side. Write fields are tied to their inert values;
  * req/resp handshake pass through. Used where a read-only stream enters a read/write device (register slice,
  * crossbar, cache bottom).
  */
class LiteBusROToRW(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int
) extends Module {

  val io = IO(new Bundle {
    val ro = Flipped(new LiteBusRO(addrWidth, dataWidth, userWidth, idWidth))
    val rw = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)
  })

  // Req: read-only side consumes, write side produces (write payload inert).
  io.rw.req.valid := io.ro.req.valid
  io.rw.req.bits.addr := io.ro.req.bits.addr
  io.rw.req.bits.user := io.ro.req.bits.user
  io.rw.req.bits.id := io.ro.req.bits.id
  io.rw.req.bits.wdata := 0.U
  io.rw.req.bits.wen := false.B
  io.rw.req.bits.wstrb := 0.U
  io.ro.req.ready := io.rw.req.ready

  // Resp: write side consumes, read-only side produces.
  io.ro.resp.valid := io.rw.resp.valid
  io.ro.resp.bits := io.rw.resp.bits
  io.rw.resp.ready := io.ro.resp.ready
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

  // Request direction: one-stage register, plain Decoupled (no flush port), same handshake as PipelineConnect.
  val reqValid = RegInit(false.B)
  val reqData = RegEnable(io.in.req.bits, io.in.req.fire)
  io.in.req.ready := !reqValid || io.out.req.ready
  io.out.req.valid := reqValid
  io.out.req.bits := reqData
  reqValid := MuxCase(reqValid, Seq(io.in.req.fire -> true.B, io.out.req.fire -> false.B))

  // Response side: tiny FIFO + registered dequeue output.
  // The FIFO stores entries; the dequeue is locked into `respDeqData` and held until
  // consumed, so `io.in.resp.bits` is register-driven. This cuts the combinational
  // FIFO-read fanout that previously reached the I-Cache refill path (STA critical:
  // 129-fanout net from respQ read to SetAssoc.bypassDataReg). Cost: +1 cycle response
  // latency, which LiteBus allows (no fixed-latency assumption).
  val respQ = Reg(Vec(2, new LiteResp(dataWidth, userWidth, idWidth)))
  val head = RegInit(0.U(1.W))
  val tail = RegInit(0.U(1.W))
  val count = RegInit(0.U(2.W))

  val canEnq = count =/= 2.U
  val canDeq = count =/= 0.U

  // Dequeue output stage: prefetch the next FIFO entry on the same cycle the current
  // one is consumed, so back-to-back responses keep one-per-cycle throughput.
  val respDeqValid = RegInit(false.B)
  val respDeqData = RegInit(0.U.asTypeOf(new LiteResp(dataWidth, userWidth, idWidth)))

  io.out.resp.ready := canEnq

  io.in.resp.valid := respDeqValid
  io.in.resp.bits := respDeqData

  val enqFire = io.out.resp.valid && io.out.resp.ready
  val deqFire = io.in.resp.valid && io.in.resp.ready
  val deqTake = (canDeq && !respDeqValid) || (deqFire && canDeq && count > 1.U)

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
