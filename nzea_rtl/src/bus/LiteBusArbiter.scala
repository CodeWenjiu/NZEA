package nzea_rtl

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PriorityEncoder, UIntToOH}

/** Round-robin multi-master arbiter for LiteBusRW.
  *
  * - N masters -> 1 downstream LiteBusRW.
  * - Single outstanding request globally.
  * - Response is routed back to the granted master.
  */
class LiteBusRWArbiter(
  numMasters: Int,
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int
) extends Module {
  require(numMasters >= 1, s"numMasters must be >= 1, got $numMasters")
  private val idxWidth = log2Ceil(numMasters.max(2))

  val io = IO(new Bundle {
    val in = Vec(numMasters, Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth)))
    val out = new LiteBusRW(addrWidth, dataWidth, userWidth)
  })

  val flushFromMasters = io.in.map(_.resp.flush).reduce(_ || _)
  val flush = flushFromMasters || io.out.req.flush
  io.out.resp.flush := flushFromMasters
  io.in.foreach(_.req.flush := flush)

  val reqValidVec = VecInit(io.in.map(_.req.valid))
  val hasReq = reqValidVec.asUInt.orR

  val rrPtr = RegInit(0.U(idxWidth.W))

  val rotatedReq = Wire(Vec(numMasters, Bool()))
  for (i <- 0 until numMasters) {
    rotatedReq(i) := reqValidVec((i.U + rrPtr) % numMasters.U)
  }
  val relIdx = PriorityEncoder(rotatedReq.asUInt)
  val sumIdx = relIdx +& rrPtr
  val grantIdx = Mux(sumIdx >= numMasters.U, sumIdx - numMasters.U, sumIdx)(idxWidth - 1, 0)

  val grantOH = Wire(UInt(numMasters.W))
  grantOH := 0.U
  when(hasReq) {
    grantOH := UIntToOH(grantIdx, numMasters)
  }

  val inflightValid = RegInit(false.B)
  val inflightIdx = RegInit(0.U(idxWidth.W))

  val canIssue = !flush && !inflightValid && hasReq
  io.out.req.valid := canIssue
  io.out.req.bits := 0.U.asTypeOf(io.out.req.bits)
  when(hasReq) {
    io.out.req.bits := Mux1H(grantOH, io.in.map(_.req.bits))
  }

  io.in.zipWithIndex.foreach { case (m, i) =>
    m.req.ready := canIssue && io.out.req.ready && grantOH(i)
    m.resp.valid := inflightValid && io.out.resp.valid && inflightIdx === i.U
    m.resp.bits := io.out.resp.bits
  }

  io.out.resp.ready := false.B
  when(inflightValid) {
    io.out.resp.ready := Mux1H(
      io.in.indices.map(i => (inflightIdx === i.U) -> io.in(i).resp.ready)
    )
  }

  val reqFire = io.out.req.valid && io.out.req.ready
  val respFire = io.out.resp.valid && io.out.resp.ready

  when(reqFire) {
    inflightValid := true.B
    inflightIdx := grantIdx
    rrPtr := Mux(grantIdx === (numMasters - 1).U, 0.U, grantIdx + 1.U)
  }

  when(flush || respFire) {
    inflightValid := false.B
  }
}

/** Bridge for sharing a LiteBusRW fabric with a read-only master.
  *
  * Converts RO request into RW request with `wen=false` and zero write payload.
  */
class LiteBusROToRW(addrWidth: Int, dataWidth: Int, userWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRO(addrWidth, dataWidth, userWidth))
    val out = new LiteBusRW(addrWidth, dataWidth, userWidth)
  })

  io.out.req.valid := io.in.req.valid
  io.out.req.bits.addr := io.in.req.bits.addr
  io.out.req.bits.wdata := 0.U
  io.out.req.bits.wen := false.B
  io.out.req.bits.wstrb := 0.U
  io.out.req.bits.user := io.in.req.bits.user
  io.in.req.ready := io.out.req.ready
  io.in.req.flush := io.out.req.flush

  io.in.resp.valid := io.out.resp.valid
  io.in.resp.bits.data := io.out.resp.bits.data
  io.in.resp.bits.user := io.out.resp.bits.user
  io.out.resp.flush := io.in.resp.flush
  io.out.resp.ready := io.in.resp.ready
}
