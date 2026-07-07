package nzea_rtl

import chisel3._

/** Random-stall latency wrapper for a [[FabricBusRW]] channel.
  *
  * Inserts independent MCG backpressure on req and resp directions. Flush signals bypass the stall pipes (passthrough),
  * leaving epoch matching in the IFU to drain unwanted responses.
  */
class FabricBusRandomStall(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    expectedCycles: Double
) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val out = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
  })

  // ── Request direction ──
  private val reqPipe = Module(new RandomStallPipe(chiselTypeOf(io.in.req.bits), expectedCycles))
  reqPipe.io.in.valid := io.in.req.valid
  reqPipe.io.in.bits := io.in.req.bits
  io.in.req.ready := reqPipe.io.in.ready
  io.out.req.valid := reqPipe.io.out.valid
  io.out.req.bits := reqPipe.io.out.bits
  reqPipe.io.out.ready := io.out.req.ready
  io.in.req.flush := io.out.req.flush
  reqPipe.io.flush := io.in.resp.flush

  // ── Response direction ──
  private val respPipe = Module(new RandomStallPipe(chiselTypeOf(io.out.resp.bits), expectedCycles))
  respPipe.io.flush := io.in.resp.flush
  respPipe.io.in.valid := io.out.resp.valid
  respPipe.io.in.bits := io.out.resp.bits
  io.out.resp.ready := respPipe.io.in.ready
  io.in.resp.valid := respPipe.io.out.valid
  io.in.resp.bits := respPipe.io.out.bits
  respPipe.io.out.ready := io.in.resp.ready
  io.out.resp.flush := io.in.resp.flush
}
