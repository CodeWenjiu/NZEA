package nzea_rtl

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.{log2Ceil, Mux1H, PopCount, PriorityEncoder, UIntToOH}
import scala.collection.mutable.ArrayBuffer

/** MxN LiteBusRW crossbar with per-slave round-robin arbitration.
  *
  * Properties:
  * - Parallel issue to different slaves is supported.
  * - Per-slave arbitration is round-robin across all requesting masters.
  * - Single outstanding per master and per slave (no ID-based OoO routing).
  * - Decode miss is accepted per master and returns zero data with req.user echoed.
  */
class LiteBusRWCrossbar private (
  numMasters: Int,
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int,
  ranges: Seq[LiteBusAddrRange]
) extends Module {
  require(numMasters >= 1, s"numMasters must be >= 1, got $numMasters")
  LiteBusXbarUtil.validateRanges(addrWidth, ranges)

  private val numSlaves = ranges.length
  private val mIdxWidth = log2Ceil(numMasters.max(2))
  private val sIdxWidth = log2Ceil(numSlaves.max(2))

  val io = IO(new Bundle {
    val in = Vec(numMasters, Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth)))
    val out = Vec(numSlaves, new LiteBusRW(addrWidth, dataWidth, userWidth))
    val decodeMiss = Output(Vec(numMasters, Bool()))
  })

  val flushFromMasters = io.in.map(_.resp.flush).reduce(_ || _)
  val flushFromSlaves = io.out.map(_.req.flush).reduce(_ || _)
  val flush = flushFromMasters || flushFromSlaves

  io.out.foreach(_.resp.flush := flush)
  io.in.foreach(_.req.flush := flush)

  val masterInflight = RegInit(VecInit.fill(numMasters)(false.B))
  val masterIsMiss = RegInit(VecInit.fill(numMasters)(false.B))
  val masterSlave = RegInit(VecInit.fill(numMasters)(0.U(sIdxWidth.W)))
  val masterMissUser = Reg(Vec(numMasters, UInt(userWidth.W)))

  val slaveInflight = RegInit(VecInit.fill(numSlaves)(false.B))
  val slaveOwner = RegInit(VecInit.fill(numSlaves)(0.U(mIdxWidth.W)))
  val slaveRrPtr = RegInit(VecInit.fill(numSlaves)(0.U(mIdxWidth.W)))

  val eligible = Wire(Vec(numMasters, Bool()))
  val hits = Wire(Vec(numMasters, Vec(numSlaves, Bool())))
  val hasHit = Wire(Vec(numMasters, Bool()))
  for (m <- 0 until numMasters) {
    eligible(m) := io.in(m).req.valid && !masterInflight(m) && !flush
    for (s <- 0 until numSlaves) {
      val r = ranges(s)
      hits(m)(s) := (io.in(m).req.bits.addr >= r.base.U(addrWidth.W)) &&
        (io.in(m).req.bits.addr < r.endExclusive.U(addrWidth.W))
    }
    hasHit(m) := hits(m).asUInt.orR
    when(io.in(m).req.valid) {
      assert(PopCount(hits(m).asUInt) <= 1.U, s"LiteBusRWCrossbar: address ranges overlap for master $m")
    }
  }

  val issueValid = Wire(Vec(numSlaves, Bool()))
  val issueGrantIdx = Wire(Vec(numSlaves, UInt(mIdxWidth.W)))
  val issueGrantOH = Wire(Vec(numSlaves, UInt(numMasters.W)))
  val hitReqFire = Wire(Vec(numSlaves, Bool()))

  for (s <- 0 until numSlaves) {
    val candidates = Wire(Vec(numMasters, Bool()))
    for (m <- 0 until numMasters) {
      candidates(m) := eligible(m) && hits(m)(s) && !slaveInflight(s)
    }
    val hasCandidate = candidates.asUInt.orR

    val rotated = Wire(Vec(numMasters, Bool()))
    for (i <- 0 until numMasters) {
      rotated(i) := candidates((i.U + slaveRrPtr(s)) % numMasters.U)
    }
    val relIdx = PriorityEncoder(rotated.asUInt)
    val sumIdx = relIdx +& slaveRrPtr(s)
    val grantIdx = Mux(sumIdx >= numMasters.U, sumIdx - numMasters.U, sumIdx)(mIdxWidth - 1, 0)
    val grantOH = Mux(hasCandidate, UIntToOH(grantIdx, numMasters), 0.U(numMasters.W))

    issueValid(s) := hasCandidate && !flush
    issueGrantIdx(s) := grantIdx
    issueGrantOH(s) := grantOH

    io.out(s).req.valid := issueValid(s)
    io.out(s).req.bits := 0.U.asTypeOf(io.out(s).req.bits)
    when(hasCandidate) {
      io.out(s).req.bits := Mux1H((0 until numMasters).map(m => grantOH(m) -> io.in(m).req.bits))
    }

    hitReqFire(s) := io.out(s).req.valid && io.out(s).req.ready
  }

  val missReqFire = Wire(Vec(numMasters, Bool()))
  for (m <- 0 until numMasters) {
    val readyFromHits = issueValid.zip(issueGrantOH).zip(io.out).map { case ((v, oh), out) =>
      v && oh(m) && out.req.ready
    }.foldLeft(false.B)(_ || _)
    val miss = eligible(m) && !hasHit(m)
    io.in(m).req.ready := miss || readyFromHits
    missReqFire(m) := miss && io.in(m).req.ready
    io.decodeMiss(m) := missReqFire(m)
  }

  for (m <- 0 until numMasters) {
    val missRespValid = masterInflight(m) && masterIsMiss(m)
    val slaveIdx = masterSlave(m)
    val hitRespValid = masterInflight(m) && !masterIsMiss(m) &&
      slaveInflight(slaveIdx) && slaveOwner(slaveIdx) === m.U && io.out(slaveIdx).resp.valid
    io.in(m).resp.valid := missRespValid || hitRespValid
    io.in(m).resp.bits.data := Mux(masterIsMiss(m), 0.U(dataWidth.W), io.out(slaveIdx).resp.bits.data)
    io.in(m).resp.bits.user := Mux(masterIsMiss(m), masterMissUser(m), io.out(slaveIdx).resp.bits.user)
  }

  val hitRespFire = Wire(Vec(numSlaves, Bool()))
  for (s <- 0 until numSlaves) {
    io.out(s).resp.ready := false.B
    when(slaveInflight(s)) {
      val owner = slaveOwner(s)
      io.out(s).resp.ready := io.in(owner).resp.ready
    }
    hitRespFire(s) := slaveInflight(s) && io.out(s).resp.valid && io.out(s).resp.ready
  }

  val missRespFire = Wire(Vec(numMasters, Bool()))
  for (m <- 0 until numMasters) {
    missRespFire(m) := masterInflight(m) && masterIsMiss(m) && io.in(m).resp.valid && io.in(m).resp.ready
  }

  for (s <- 0 until numSlaves) {
    when(hitReqFire(s)) {
      val g = issueGrantIdx(s)
      slaveInflight(s) := true.B
      slaveOwner(s) := g
      masterInflight(g) := true.B
      masterIsMiss(g) := false.B
      masterSlave(g) := s.U
      slaveRrPtr(s) := Mux(g === (numMasters - 1).U, 0.U, g + 1.U)
    }
  }

  for (m <- 0 until numMasters) {
    when(missReqFire(m)) {
      masterInflight(m) := true.B
      masterIsMiss(m) := true.B
      masterMissUser(m) := io.in(m).req.bits.user
    }
  }

  for (s <- 0 until numSlaves) {
    when(hitRespFire(s)) {
      val owner = slaveOwner(s)
      slaveInflight(s) := false.B
      masterInflight(owner) := false.B
      masterIsMiss(owner) := false.B
    }
  }

  for (m <- 0 until numMasters) {
    when(missRespFire(m)) {
      masterInflight(m) := false.B
      masterIsMiss(m) := false.B
    }
  }

  when(flush) {
    for (m <- 0 until numMasters) {
      masterInflight(m) := false.B
      masterIsMiss(m) := false.B
    }
    for (s <- 0 until numSlaves) {
      slaveInflight(s) := false.B
    }
  }

}

object LiteBusRWCrossbar {
  /** Auto-link builder:
    * infer bus shape and number of masters from incremental `<>` links, then build xbar once. */
  final class AutoLinkBuilder private[LiteBusRWCrossbar] (ranges: Seq[LiteBusAddrRange]) {
    private val masters = ArrayBuffer.empty[LiteBusRW]
    private val slaves = ArrayBuffer.empty[LiteBusRW]
    private var shape: Option[(Int, Int, Int)] = None
    private var builtXbar: Option[LiteBusRWCrossbar] = None

    private def captureShape(bus: LiteBusRW): Unit = {
      shape match {
        case None =>
          shape = Some((bus.addrWidth, bus.dataWidth, bus.userWidth))
        case Some((aw, dw, uw)) =>
          require(
            bus.addrWidth == aw && bus.dataWidth == dw && bus.userWidth == uw,
            s"inconsistent LiteBusRW shape: expected ($aw, $dw, $uw), got (${bus.addrWidth}, ${bus.dataWidth}, ${bus.userWidth})"
          )
      }
    }

    /** Incrementally collect one bus endpoint. */
    def <>(bus: LiteBusRW): this.type = {
      require(builtXbar.isEmpty, "cannot add links after xbar is built")
      captureShape(bus)
      DataMirror.directionOf(bus.req.valid) match {
        case ActualDirection.Output => masters += bus
        case ActualDirection.Input => slaves += bus
        case other =>
          throw new IllegalArgumentException(
            s"cannot infer LiteBus direction from req.valid=$other. " +
              "Connect xbar.io.in/out(idx) explicitly for ambiguous endpoints."
          )
      }
      this
    }

    /** Materialize the crossbar and apply all captured links. */
    def build(): LiteBusRWCrossbar = {
      builtXbar.getOrElse {
        require(masters.nonEmpty, "at least one master must be linked before build")
        require(slaves.nonEmpty, "at least one slave must be linked before build")
        require(ranges.length == slaves.length, s"ranges(${ranges.length}) must match linked slaves(${slaves.length})")
        val (addrWidth, dataWidth, userWidth) = shape.getOrElse {
          throw new IllegalStateException("no bus shape captured; link at least one bus before build")
        }

        val xbar = Module(new LiteBusRWCrossbar(masters.length, addrWidth, dataWidth, userWidth, ranges))
        masters.zipWithIndex.foreach { case (m, i) => xbar.io.in(i) <> m }
        slaves.zipWithIndex.foreach { case (s, i) => xbar.io.out(i) <> s }
        builtXbar = Some(xbar)
        xbar
      }
    }
  }

  /** Preferred user-facing API: no explicit numMasters.
    *
    * Example:
    * {{{
    * val xbar = LiteBusRWCrossbar(ranges) { x =>
    *   x <> cpu.io.ibus
    *   x <> cpu.io.dbus
    *   x <> uart.io.bus
    *   x <> sdram.io.bus
    * }
    * }}}
    */
  def apply(ranges: Seq[LiteBusAddrRange])(link: AutoLinkBuilder => Unit): LiteBusRWCrossbar = {
    val builder = new AutoLinkBuilder(ranges)
    link(builder)
    builder.build()
  }

}

private object LiteBusXbarUtil {
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
