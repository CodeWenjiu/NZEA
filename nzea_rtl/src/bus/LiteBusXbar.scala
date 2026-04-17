package nzea_rtl

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount, PriorityEncoder}

/** Address window used by [[LiteBusROXbar]] and [[LiteBusRWXbar]]. */
case class LiteBusAddrRange(base: BigInt, size: BigInt) {
  require(base >= 0, s"base must be >= 0, got $base")
  require(size > 0, s"size must be > 0, got $size")
  val endExclusive: BigInt = base + size
}

/** Shared decode helpers for LiteBus xbars. */
private object LiteBusXbarDecode {
  def hitVec(addr: UInt, addrWidth: Int, ranges: Seq[LiteBusAddrRange]): Seq[Bool] = {
    ranges.map { r =>
      (addr >= r.base.U(addrWidth.W)) && (addr < r.endExclusive.U(addrWidth.W))
    }
  }

  def validateRanges(addrWidth: Int, ranges: Seq[LiteBusAddrRange]): Unit = {
    require(ranges.nonEmpty, "at least one slave range is required")
    val addrSpaceEnd = BigInt(1) << addrWidth
    ranges.foreach { r =>
      require(
        r.endExclusive <= addrSpaceEnd,
        s"range [0x${r.base.toString(16)}, 0x${r.endExclusive.toString(16)}) exceeds addr width $addrWidth"
      )
    }
  }
}

/** 1xN read-only LiteBus xbar with address decode.
  *
  * - Single outstanding request at a time.
  * - `decodeMiss` accepts request and returns zero data with req.user echoed back.
  */
class LiteBusROXbar(
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int,
  ranges: Seq[LiteBusAddrRange]
) extends Module {
  LiteBusXbarDecode.validateRanges(addrWidth, ranges)
  private val numSlaves = ranges.length
  private val selWidth = log2Ceil(numSlaves.max(2))

  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRO(addrWidth, dataWidth, userWidth))
    val out = Vec(numSlaves, new LiteBusRO(addrWidth, dataWidth, userWidth))
    val decodeMiss = Output(Bool())
  })

  val flush = io.in.resp.flush
  io.in.req.flush := flush

  io.out.foreach { s =>
    s.req.valid := false.B
    s.req.bits := io.in.req.bits
    s.resp.ready := false.B
    s.resp.flush := flush
  }

  val hits = VecInit(LiteBusXbarDecode.hitVec(io.in.req.bits.addr, addrWidth, ranges))
  when(io.in.req.valid) {
    assert(PopCount(hits) <= 1.U, "LiteBusROXbar: address ranges overlap")
  }
  val hit = hits.asUInt.orR
  val sel = PriorityEncoder(hits)

  val inflightValid = RegInit(false.B)
  val inflightSel = RegInit(0.U(selWidth.W))
  val inflightMiss = RegInit(false.B)
  val missUser = Reg(UInt(userWidth.W))

  val selectedReady = Mux1H(hits, io.out.map(_.req.ready))
  val canAccept = !flush && !inflightValid && Mux(hit, selectedReady, true.B)
  io.in.req.ready := canAccept
  val reqFire = io.in.req.valid && io.in.req.ready

  io.decodeMiss := reqFire && !hit

  io.out.zipWithIndex.foreach { case (s, i) =>
    s.req.valid := io.in.req.valid && !flush && !inflightValid && hit && sel === i.U
  }

  when(reqFire) {
    inflightValid := true.B
    inflightSel := sel
    inflightMiss := !hit
    when(!hit) { missUser := io.in.req.bits.user }
  }

  val slaveRespSel = VecInit(io.out.indices.map(i =>
    inflightValid && !inflightMiss && inflightSel === i.U && io.out(i).resp.valid
  ))
  val slaveRespValid = slaveRespSel.asUInt.orR
  val slaveRespBits = Mux1H(slaveRespSel, io.out.map(_.resp.bits))

  val missRespValid = inflightValid && inflightMiss
  io.in.resp.valid := missRespValid || slaveRespValid
  io.in.resp.bits.data := Mux(missRespValid, 0.U(dataWidth.W), slaveRespBits.data)
  io.in.resp.bits.user := Mux(missRespValid, missUser, slaveRespBits.user)

  io.out.zipWithIndex.foreach { case (s, i) =>
    s.resp.ready := io.in.resp.ready && inflightValid && !inflightMiss && inflightSel === i.U
  }

  val respFire = io.in.resp.valid && io.in.resp.ready
  when(flush || respFire) {
    inflightValid := false.B
    inflightMiss := false.B
  }
}

/** 1xN read-write LiteBus xbar with address decode.
  *
  * - Single outstanding request at a time.
  * - `decodeMiss` accepts request and returns zero data with req.user echoed back.
  */
class LiteBusRWXbar(
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int,
  ranges: Seq[LiteBusAddrRange]
) extends Module {
  LiteBusXbarDecode.validateRanges(addrWidth, ranges)
  private val numSlaves = ranges.length
  private val selWidth = log2Ceil(numSlaves.max(2))

  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth))
    val out = Vec(numSlaves, new LiteBusRW(addrWidth, dataWidth, userWidth))
    val decodeMiss = Output(Bool())
  })

  val flush = io.in.resp.flush
  io.in.req.flush := flush

  io.out.foreach { s =>
    s.req.valid := false.B
    s.req.bits := io.in.req.bits
    s.resp.ready := false.B
    s.resp.flush := flush
  }

  val hits = VecInit(LiteBusXbarDecode.hitVec(io.in.req.bits.addr, addrWidth, ranges))
  when(io.in.req.valid) {
    assert(PopCount(hits) <= 1.U, "LiteBusRWXbar: address ranges overlap")
  }
  val hit = hits.asUInt.orR
  val sel = PriorityEncoder(hits)

  val inflightValid = RegInit(false.B)
  val inflightSel = RegInit(0.U(selWidth.W))
  val inflightMiss = RegInit(false.B)
  val missUser = Reg(UInt(userWidth.W))

  val selectedReady = Mux1H(hits, io.out.map(_.req.ready))
  val canAccept = !flush && !inflightValid && Mux(hit, selectedReady, true.B)
  io.in.req.ready := canAccept
  val reqFire = io.in.req.valid && io.in.req.ready

  io.decodeMiss := reqFire && !hit

  io.out.zipWithIndex.foreach { case (s, i) =>
    s.req.valid := io.in.req.valid && !flush && !inflightValid && hit && sel === i.U
  }

  when(reqFire) {
    inflightValid := true.B
    inflightSel := sel
    inflightMiss := !hit
    when(!hit) { missUser := io.in.req.bits.user }
  }

  val slaveRespSel = VecInit(io.out.indices.map(i =>
    inflightValid && !inflightMiss && inflightSel === i.U && io.out(i).resp.valid
  ))
  val slaveRespValid = slaveRespSel.asUInt.orR
  val slaveRespBits = Mux1H(slaveRespSel, io.out.map(_.resp.bits))

  val missRespValid = inflightValid && inflightMiss
  io.in.resp.valid := missRespValid || slaveRespValid
  io.in.resp.bits.data := Mux(missRespValid, 0.U(dataWidth.W), slaveRespBits.data)
  io.in.resp.bits.user := Mux(missRespValid, missUser, slaveRespBits.user)

  io.out.zipWithIndex.foreach { case (s, i) =>
    s.resp.ready := io.in.resp.ready && inflightValid && !inflightMiss && inflightSel === i.U
  }

  val respFire = io.in.resp.valid && io.in.resp.ready
  when(flush || respFire) {
    inflightValid := false.B
    inflightMiss := false.B
  }
}
