package nzea_rtl

import chisel3._

/** Random-stall latency wrapper for a [[LiteBusRW]] channel.
  *
  * Inserts independent MCG backpressure on req and resp directions. Cancellation is master-side (digest), so stale
  * responses simply take longer to drain.
  */
class LiteBusRandomStall(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    expectedCycles: Double
) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val out = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)
  })

  // ── Request direction ──
  private val reqPipe = Module(new RandomStallPipe(chiselTypeOf(io.in.req.bits), expectedCycles))
  reqPipe.io.flush := false.B // no bus flush; stale entries drain at their own pace
  reqPipe.io.in.valid := io.in.req.valid
  reqPipe.io.in.bits := io.in.req.bits
  io.in.req.ready := reqPipe.io.in.ready
  io.out.req.valid := reqPipe.io.out.valid
  io.out.req.bits := reqPipe.io.out.bits
  reqPipe.io.out.ready := io.out.req.ready

  // ── Response direction ──
  private val respPipe = Module(new RandomStallPipe(chiselTypeOf(io.out.resp.bits), expectedCycles))
  respPipe.io.flush := false.B
  respPipe.io.in.valid := io.out.resp.valid
  respPipe.io.in.bits := io.out.resp.bits
  io.out.resp.ready := respPipe.io.in.ready
  io.in.resp.valid := respPipe.io.out.valid
  io.in.resp.bits := respPipe.io.out.bits
  respPipe.io.out.ready := io.in.resp.ready
}
