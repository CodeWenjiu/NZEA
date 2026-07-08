package nzea_rtl

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.{log2Ceil, Mux1H, MuxCase, OHToUInt, PopCount, PriorityEncoder, PriorityEncoderOH, UIntToOH}
import scala.collection.mutable.ArrayBuffer

/** MxN FabricBusRW crossbar with per-slave round-robin arbitration.
  *
  * Properties:
  *   - Parallel issue to different slaves is supported.
  *   - Per-slave arbitration is round-robin across all requesting masters.
  *   - Multiple outstanding requests are supported per slave.
  *   - Responses are routed by request ID, so out-of-order responses are supported.
  *   - Decode miss is accepted per master and returns zero data with req.user/req.id echoed.
  */
class FabricBusRWCrossbar(
    numMasters: Int,
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    ranges: Seq[FabricAddrRange],
    perSlaveOutstanding: Int,
    perMasterRespDepth: Int = 8
) extends Module {
  require(numMasters >= 1, s"numMasters must be >= 1, got $numMasters")
  require(perSlaveOutstanding >= 1, s"perSlaveOutstanding must be >= 1, got $perSlaveOutstanding")
  require(perMasterRespDepth >= 1, s"perMasterRespDepth must be >= 1, got $perMasterRespDepth")

  require(
    perSlaveOutstanding <= (1 << idWidth),
    s"perSlaveOutstanding($perSlaveOutstanding) must fit idWidth($idWidth)"
  )

  FabricBusXbarUtil.validateRanges(addrWidth, ranges)

  private val numSlaves = ranges.length
  private val mIdxWidth = log2Ceil(numMasters.max(2))
  private val sIdxWidth = log2Ceil(numSlaves.max(2))
  private val entryIdxWidth = log2Ceil(perSlaveOutstanding.max(2))
  private val respQPtrWidth = log2Ceil(perMasterRespDepth.max(2))
  private val respQCntWidth = log2Ceil(perMasterRespDepth + 1)

  val io = IO(new Bundle {
    val in = Vec(numMasters, Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)))
    val out = Vec(numSlaves, new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val decodeMiss = Output(Vec(numMasters, Bool()))
  })

  private def wrapInc(x: UInt, n: Int): UInt = {
    if (n <= 1) 0.U else Mux(x === (n - 1).U, 0.U, x + 1.U)
  }

  private val flushFromMasters = io.in.map(_.resp.flush).reduce(_ || _)
  private val flushFromSlaves = io.out.map(_.req.flush).reduce(_ || _)
  private val flush = flushFromMasters || flushFromSlaves

  io.out.foreach(_.resp.flush := flush)
  io.in.foreach(_.req.flush := flush)

  // Outstanding owner table per slave: (id -> owner master).
  private val ownerValid = RegInit(VecInit.fill(numSlaves)(VecInit.fill(perSlaveOutstanding)(false.B)))
  private val ownerId = Reg(Vec(numSlaves, Vec(perSlaveOutstanding, UInt(idWidth.W))))
  private val ownerMaster = Reg(Vec(numSlaves, Vec(perSlaveOutstanding, UInt(mIdxWidth.W))))

  // Decode request targets.
  val eligible = Wire(Vec(numMasters, Bool()))
  val hits = Wire(Vec(numMasters, Vec(numSlaves, Bool())))
  val hasHit = Wire(Vec(numMasters, Bool()))

  for (m <- 0 until numMasters) {
    eligible(m) := io.in(m).req.valid && !flush
    for (s <- 0 until numSlaves) {
      val r = ranges(s)
      val baseHit = io.in(m).req.bits.addr >= r.base.U(addrWidth.W)
      val topEnd = r.endExclusive == (BigInt(1) << addrWidth)
      val endHit = if (topEnd) true.B else io.in(m).req.bits.addr < r.endExclusive.U(addrWidth.W)
      hits(m)(s) := baseHit && endHit
    }
    hasHit(m) := hits(m).asUInt.orR
    when(io.in(m).req.valid) {
      assert(PopCount(hits(m).asUInt) <= 1.U, s"FabricBusRWCrossbar: address ranges overlap for master $m")
    }
  }

  // Request arbitration per slave.
  val slaveReqRrPtr = RegInit(VecInit.fill(numSlaves)(0.U(mIdxWidth.W)))
  val ownerEnqReady = Wire(Vec(numSlaves, Bool()))
  val ownerFreeOH = Wire(Vec(numSlaves, UInt(perSlaveOutstanding.W)))
  val issueValid = Wire(Vec(numSlaves, Bool()))
  val issueGrantIdx = Wire(Vec(numSlaves, UInt(mIdxWidth.W)))
  val issueGrantOH = Wire(Vec(numSlaves, UInt(numMasters.W)))
  val hitReqFire = Wire(Vec(numSlaves, Bool()))

  for (s <- 0 until numSlaves) {
    val freeMask = Wire(Vec(perSlaveOutstanding, Bool()))
    for (e <- 0 until perSlaveOutstanding) freeMask(e) := !ownerValid(s)(e)
    ownerEnqReady(s) := freeMask.asUInt.orR
    ownerFreeOH(s) := PriorityEncoderOH(freeMask.asUInt)

    val candidates = Wire(Vec(numMasters, Bool()))
    for (m <- 0 until numMasters) {
      candidates(m) := eligible(m) && hits(m)(s) && ownerEnqReady(s)
    }
    val hasCandidate = candidates.asUInt.orR

    val rotated = Wire(Vec(numMasters, Bool()))
    for (i <- 0 until numMasters) {
      val idx =
        if (numMasters <= 1) 0.U
        else {
          val sum = i.U +& slaveReqRrPtr(s)
          Mux(sum >= numMasters.U, sum - numMasters.U, sum)(mIdxWidth - 1, 0)
        }
      rotated(i) := candidates(idx)
    }
    val relIdx = PriorityEncoder(rotated.asUInt)
    val sumIdx = relIdx +& slaveReqRrPtr(s)
    val grantIdx = Mux(sumIdx >= numMasters.U, sumIdx - numMasters.U, sumIdx)(mIdxWidth - 1, 0)
    val grantOH = Mux(hasCandidate, UIntToOH(grantIdx, numMasters), 0.U(numMasters.W))

    issueValid(s) := hasCandidate && !flush
    issueGrantIdx(s) := grantIdx
    issueGrantOH(s) := grantOH

    io.out(s).req.valid := issueValid(s)
    io.out(s).req.bits := 0.U.asTypeOf(io.out(s).req.bits)
    when(hasCandidate) {
      io.out(s).req.bits := Mux1H((0 until numMasters).map(m => grantOH(m) -> io.in(m).req.bits))
      // Re-map outward request id to owner-slot tag to guarantee per-slave uniqueness
      // regardless of master-local id reuse/wrap.
      io.out(s).req.bits.id := OHToUInt(ownerFreeOH(s)).asUInt
    }

    hitReqFire(s) := io.out(s).req.valid && io.out(s).req.ready
    when(hitReqFire(s)) {
      slaveReqRrPtr(s) := wrapInc(issueGrantIdx(s), numMasters)
    }
  }

  // Per-master req.ready and decode-miss handling.
  val missPending = RegInit(VecInit.fill(numMasters)(false.B))
  val missUser = Reg(Vec(numMasters, UInt(userWidth.W)))
  val missId = Reg(Vec(numMasters, UInt(idWidth.W)))
  val respQHead = RegInit(VecInit.fill(numMasters)(0.U(respQPtrWidth.W)))
  val respQTail = RegInit(VecInit.fill(numMasters)(0.U(respQPtrWidth.W)))
  val respQCount = RegInit(VecInit.fill(numMasters)(0.U(respQCntWidth.W)))
  val respQData = Reg(Vec(numMasters, Vec(perMasterRespDepth, UInt(dataWidth.W))))
  val respQUser = Reg(Vec(numMasters, Vec(perMasterRespDepth, UInt(userWidth.W))))
  val respQId = Reg(Vec(numMasters, Vec(perMasterRespDepth, UInt(idWidth.W))))
  val missReqFire = Wire(Vec(numMasters, Bool()))
  val missRespFire = Wire(Vec(numMasters, Bool()))

  for (m <- 0 until numMasters) {
    val readyFromHits = issueValid
      .zip(issueGrantOH)
      .zip(io.out)
      .map { case ((v, oh), out) =>
        v && oh(m) && out.req.ready
      }
      .foldLeft(false.B)(_ || _)

    val miss = io.in(m).req.valid && !flush && !hasHit(m)
    val missReady = !missPending(m)
    io.in(m).req.ready := readyFromHits || (miss && missReady)
    missReqFire(m) := miss && missReady
    io.decodeMiss(m) := missReqFire(m)
  }

  // Response matching by ID in each slave outstanding table.
  val respMatchOH = Wire(Vec(numSlaves, UInt(perSlaveOutstanding.W)))
  val respMatched = Wire(Vec(numSlaves, Bool()))
  val respMatchIdx = Wire(Vec(numSlaves, UInt(entryIdxWidth.W)))
  val respOwner = Wire(Vec(numSlaves, UInt(mIdxWidth.W)))
  val respOrigId = Wire(Vec(numSlaves, UInt(idWidth.W)))

  for (s <- 0 until numSlaves) {
    val respTagIdx = io.out(s).resp.bits.id(entryIdxWidth - 1, 0)
    val respTagValid = io.out(s).resp.bits.id === respTagIdx.asUInt
    val matchVec = Wire(Vec(perSlaveOutstanding, Bool()))
    for (e <- 0 until perSlaveOutstanding) {
      matchVec(e) := ownerValid(s)(e) && respTagValid && respTagIdx === e.U
    }
    when(io.out(s).resp.valid) {
      assert(PopCount(matchVec.asUInt) <= 1.U, s"FabricBusRWCrossbar: duplicate outstanding IDs on slave $s")
    }
    respMatchOH(s) := matchVec.asUInt
    respMatched(s) := matchVec.asUInt.orR
    respMatchIdx(s) := PriorityEncoder(matchVec.asUInt)
    respOwner(s) := Mux(respMatched(s), ownerMaster(s)(respMatchIdx(s)), 0.U)
    respOrigId(s) := Mux(respMatched(s), ownerId(s)(respMatchIdx(s)), 0.U)
  }

  // Response arbitration per master (in case multiple slaves answer same master in one cycle).
  // Selected response is either bypassed to master (if queue empty + ready) or enqueued.
  val masterRespRrPtr = RegInit(VecInit.fill(numMasters)(0.U(sIdxWidth.W)))
  val masterRespGrantOH = Wire(Vec(numMasters, UInt(numSlaves.W)))
  val masterRespDataSel = Wire(Vec(numMasters, UInt(dataWidth.W)))
  val masterRespUserSel = Wire(Vec(numMasters, UInt(userWidth.W)))
  val masterRespIdSel = Wire(Vec(numMasters, UInt(idWidth.W)))
  val masterHitAccept = Wire(Vec(numMasters, Bool()))
  val masterSlaveRespFire = Wire(Vec(numMasters, Bool()))
  val masterSelectedSlave = Wire(Vec(numMasters, UInt(sIdxWidth.W)))

  for (m <- 0 until numMasters) {
    val candidates = Wire(Vec(numSlaves, Bool()))
    for (s <- 0 until numSlaves) {
      candidates(s) := io.out(s).resp.valid && respMatched(s) && respOwner(s) === m.U
    }
    val hasCandidate = candidates.asUInt.orR

    val rotated = Wire(Vec(numSlaves, Bool()))
    for (i <- 0 until numSlaves) {
      val idx =
        if (numSlaves <= 1) 0.U
        else {
          val sum = i.U +& masterRespRrPtr(m)
          Mux(sum >= numSlaves.U, sum - numSlaves.U, sum)(sIdxWidth - 1, 0)
        }
      rotated(i) := candidates(idx)
    }
    val relIdx = PriorityEncoder(rotated.asUInt)
    val sumIdx = relIdx +& masterRespRrPtr(m)
    val grantIdx = Mux(sumIdx >= numSlaves.U, sumIdx - numSlaves.U, sumIdx)(sIdxWidth - 1, 0)
    val grantOH = Mux(hasCandidate, UIntToOH(grantIdx, numSlaves), 0.U(numSlaves.W))

    masterRespGrantOH(m) := grantOH
    masterSelectedSlave(m) := grantIdx
    masterRespDataSel(m) := Mux(hasCandidate, Mux1H(grantOH, io.out.map(_.resp.bits.data)), 0.U)
    masterRespUserSel(m) := Mux(hasCandidate, Mux1H(grantOH, io.out.map(_.resp.bits.user)), 0.U)
    masterRespIdSel(m) := Mux(hasCandidate, Mux1H(grantOH, respOrigId), 0.U)

    val qNonEmpty = respQCount(m) =/= 0.U
    val qHasSpace = respQCount(m) =/= perMasterRespDepth.U
    val canBypass = !qNonEmpty && io.in(m).resp.ready && !flush
    val bypassHit = hasCandidate && canBypass
    val bypassMiss = !hasCandidate && missPending(m) && canBypass
    val enqHit = hasCandidate && !canBypass && qHasSpace && !flush
    val enqMiss = !hasCandidate && missPending(m) && !canBypass && qHasSpace && !flush
    val enqFire = enqHit || enqMiss
    val deqFire = qNonEmpty && io.in(m).resp.ready && !flush

    val qHeadData = respQData(m)(respQHead(m))
    val qHeadUser = respQUser(m)(respQHead(m))
    val qHeadId = respQId(m)(respQHead(m))

    io.in(m).resp.valid := qNonEmpty || bypassHit || bypassMiss
    io.in(m).resp.bits.data := Mux(
      qNonEmpty,
      qHeadData,
      Mux(hasCandidate, masterRespDataSel(m), 0.U(dataWidth.W))
    )
    io.in(m).resp.bits.user := Mux(
      qNonEmpty,
      qHeadUser,
      Mux(hasCandidate, masterRespUserSel(m), missUser(m))
    )
    io.in(m).resp.bits.id := Mux(
      qNonEmpty,
      qHeadId,
      Mux(hasCandidate, masterRespIdSel(m), missId(m))
    )

    masterHitAccept(m) := bypassHit || enqHit
    missRespFire(m) := bypassMiss || enqMiss
    masterSlaveRespFire(m) := masterHitAccept(m)

    when(enqFire) {
      respQData(m)(respQTail(m)) := Mux(hasCandidate, masterRespDataSel(m), 0.U(dataWidth.W))
      respQUser(m)(respQTail(m)) := Mux(hasCandidate, masterRespUserSel(m), missUser(m))
      respQId(m)(respQTail(m)) := Mux(hasCandidate, masterRespIdSel(m), missId(m))
    }
    when(!flush) {
      when(enqFire) {
        respQTail(m) := wrapInc(respQTail(m), perMasterRespDepth)
      }
      when(deqFire) {
        respQHead(m) := wrapInc(respQHead(m), perMasterRespDepth)
      }
      respQCount(m) := MuxCase(
        respQCount(m),
        Seq(
          (enqFire && !deqFire) -> (respQCount(m) + 1.U),
          (!enqFire && deqFire) -> (respQCount(m) - 1.U)
        )
      )
    }

    when(masterSlaveRespFire(m)) {
      masterRespRrPtr(m) := wrapInc(masterSelectedSlave(m), numSlaves)
    }
  }

  // Drive slave resp.ready:
  // - matched response: only ready when owner master selects this slave and is ready.
  // - unmatched response: consume to avoid deadlock after flush/reset windows.
  val hitRespFire = Wire(Vec(numSlaves, Bool()))

  for (s <- 0 until numSlaves) {
    val ownerSel = Wire(Vec(numMasters, Bool()))
    for (m <- 0 until numMasters) {
      ownerSel(m) := respMatched(s) && respOwner(s) === m.U && masterRespGrantOH(m)(s)
    }
    val ownerSelValid = ownerSel.asUInt.orR
    val ownerAccept = Mux(
      ownerSelValid,
      Mux1H((0 until numMasters).map(m => ownerSel(m) -> masterHitAccept(m))),
      false.B
    )

    io.out(s).resp.ready := Mux(respMatched(s), ownerAccept, true.B)
    hitRespFire(s) := io.out(s).resp.valid && io.out(s).resp.ready && respMatched(s) && ownerSelValid
  }

  // Update outstanding owner tables.
  for (s <- 0 until numSlaves) {
    val alloc = hitReqFire(s)
    val freeOH = ownerFreeOH(s)
    val allocIdx = OHToUInt(freeOH)
    val allocId = Mux1H((0 until numMasters).map(m => issueGrantOH(s)(m) -> io.in(m).req.bits.id))

    when(alloc) {
      assert(freeOH.orR, s"FabricBusRWCrossbar: slave $s accepted req without free outstanding slot")
      ownerValid(s)(allocIdx) := true.B
      ownerId(s)(allocIdx) := allocId
      ownerMaster(s)(allocIdx) := issueGrantIdx(s)
    }

    when(hitRespFire(s)) {
      ownerValid(s)(respMatchIdx(s)) := false.B
    }
  }

  for (m <- 0 until numMasters) {
    when(missReqFire(m)) {
      missPending(m) := true.B
      missUser(m) := io.in(m).req.bits.user
      missId(m) := io.in(m).req.bits.id
    }
    when(missRespFire(m)) {
      missPending(m) := false.B
    }
  }

  when(flush) {
    for (m <- 0 until numMasters) {
      missPending(m) := false.B
      respQHead(m) := 0.U
      respQTail(m) := 0.U
      respQCount(m) := 0.U
    }
    for (s <- 0 until numSlaves) {
      for (e <- 0 until perSlaveOutstanding) {
        ownerValid(s)(e) := false.B
      }
    }
  }

}

object FabricBusRWCrossbar {

  /** Auto-link builder: infer bus shape and number of masters from incremental `<>` links, then build xbar once.
    */
  final class AutoLinkBuilder private[FabricBusRWCrossbar] (
      ranges: Seq[FabricAddrRange],
      perSlaveOutstanding: Int
  ) {
    private val masters = ArrayBuffer.empty[FabricBusRW]
    private val slaves = ArrayBuffer.empty[FabricBusRW]
    private var shape: Option[(Int, Int, Int, Int)] = None
    private var builtXbar: Option[FabricBusRWCrossbar] = None

    private def captureShape(bus: FabricBusRW): Unit = {
      shape match {
        case None =>
          shape = Some((bus.addrWidth, bus.dataWidth, bus.userWidth, bus.idWidth))
        case Some((aw, dw, uw, iw)) =>
          require(
            bus.addrWidth == aw && bus.dataWidth == dw && bus.userWidth == uw && bus.idWidth == iw,
            s"inconsistent FabricBusRW shape: expected ($aw, $dw, $uw, $iw), " +
              s"got (${bus.addrWidth}, ${bus.dataWidth}, ${bus.userWidth}, ${bus.idWidth})"
          )
      }
    }

    /** Incrementally collect one bus endpoint. */
    def <>(bus: FabricBusRW): this.type = {
      require(builtXbar.isEmpty, "cannot add links after xbar is built")
      captureShape(bus)
      DataMirror.directionOf(bus.req.valid) match {
        case ActualDirection.Output => masters += bus
        case ActualDirection.Input  => slaves += bus
        case other =>
          throw new IllegalArgumentException(
            s"cannot infer FabricBus direction from req.valid=$other. " +
              "Connect xbar.io.in/out(idx) explicitly for ambiguous endpoints."
          )
      }
      this
    }

    /** Materialize the crossbar and apply all captured links. */
    def build(): FabricBusRWCrossbar = {
      builtXbar.getOrElse {
        require(masters.nonEmpty, "at least one master must be linked before build")
        require(slaves.nonEmpty, "at least one slave must be linked before build")
        require(ranges.length == slaves.length, s"ranges(${ranges.length}) must match linked slaves(${slaves.length})")
        val (addrWidth, dataWidth, userWidth, idWidth) = shape.getOrElse {
          throw new IllegalStateException("no bus shape captured; link at least one bus before build")
        }

        val xbar = Module(
          new FabricBusRWCrossbar(
            masters.length,
            addrWidth,
            dataWidth,
            userWidth,
            idWidth,
            ranges,
            perSlaveOutstanding
          )
        )
        masters.zipWithIndex.foreach { case (m, i) => xbar.io.in(i) <> m }
        slaves.zipWithIndex.foreach { case (s, i) => xbar.io.out(i) <> s }
        builtXbar = Some(xbar)
        xbar
      }
    }

  }

  /** Preferred API: no explicit numMasters.
    *
    * Example:
    * {{{
    * val xbar = FabricBusRWCrossbar(ranges) { x =>
    *   x <> cpu.io.ibus
    *   x <> cpu.io.dbus
    *   x <> uart.io.bus
    *   x <> sdram.io.bus
    * }
    * }}}
    */
  def apply(
      ranges: Seq[FabricAddrRange],
      perSlaveOutstanding: Int
  )(link: AutoLinkBuilder => Unit): FabricBusRWCrossbar = {
    val builder = new AutoLinkBuilder(ranges, perSlaveOutstanding)
    link(builder)
    builder.build()
  }

}

private object FabricBusXbarUtil {

  def validateRanges(addrWidth: Int, ranges: Seq[FabricAddrRange]): Unit = {
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
