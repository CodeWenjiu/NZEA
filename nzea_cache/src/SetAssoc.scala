package nzea_cache

import chisel3._
import chisel3.util._
import nzea_rtl._

/** One cache row: tag + data stored together in SRAM. */
private class CacheRow(tagBits: Int, lineBits: Int) extends Bundle {
  val tag = UInt(tagBits.W)
  val data = UInt(lineBits.W)
}

/** Simple set-associative cache with 1-cycle read latency.
  *
  *   - `nSets` × `nWays`, both must be power-of-2
  *   - top: `LiteBusRO` (CPU side, read-only)
  *   - bottom: `LiteBusRW` (memory side, refill)
  *   - Tags stored alongside data in SRAM (one row = tag + cacheline per way)
  *
  * Pipeline: T0: addr presented → SRAM reads all ways | req registered for T1 T1: SRAM data arrives → split tag + line
  * → compare → select → output Miss → refill FSM fetches full cacheline, writes tag+data to SRAM, replays
  */
class SetAssoc(
    nSets: Int,
    nWays: Int,
    lineBits: Int,
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int = 0
) extends Module {

  require(isPow2(nSets), s"nSets=$nSets must be power of 2")
  require(isPow2(nWays), s"nWays=$nWays must be power of 2")
  require(lineBits >= dataWidth, s"lineBits=$lineBits must be >= dataWidth=$dataWidth")

  private val indexBits = log2Ceil(nSets)
  private val offsetBits = log2Ceil(lineBits / 8)
  private val wayBits = log2Ceil(nWays)
  private val tagBits = addrWidth - indexBits - offsetBits

  val io = IO(new Bundle {
    val top = Flipped(new LiteBusRO(addrWidth, dataWidth, userWidth))
    val bottom = new LiteBusRO(addrWidth, lineBits, userWidth)
  })

  // ── Combined tag+data SRAM — one per way ──
  private val row = new CacheRow(tagBits, lineBits)
  private val srams = Seq.fill(nWays)(SyncReadMem(nSets, row))

  // ── Valid bits — registers (small, need combinational read for T1 hit check) ──
  private val validArray = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(false.B)))))

  // ── Pipeline registers ──
  // T0: capture req for T1 tag compare
  private val t0Req = RegInit(0.U.asTypeOf(Valid(new Bundle {
    val addr = UInt(addrWidth.W)
    val user = UInt(userWidth.W)
  })))

  // T1: response to top
  private val t1Resp = RegInit(0.U.asTypeOf(Valid(new LiteResp(dataWidth, userWidth))))

  // ── Refill state ──
  private val refillAddr = RegInit(0.U(addrWidth.W))
  private val refillTag = RegInit(0.U(tagBits.W))
  private val refillSet = RegInit(0.U(indexBits.W))
  private val refillWay = RegInit(0.U(wayBits.W))

  private val sIdle :: sRefillReq :: sRefillWait :: Nil = Enum(3)
  private val state = RegInit(sIdle)

  // ── T0: SRAM read from input port (not from register — to align with 1-cycle SRAM latency) ──
  private val t0SetIdx = io.top.req.bits.addr(indexBits + offsetBits - 1, offsetBits)
  private val t0RdRows = VecInit(srams.map(_.read(t0SetIdx, io.top.req.fire)))

  // ── T1: tag compare + data select ──
  private val t1SetIdx = t0Req.bits.addr(indexBits + offsetBits - 1, offsetBits)
  private val t1Tag = t0Req.bits.addr(addrWidth - 1, indexBits + offsetBits)
  private val t1Valids = validArray(t1SetIdx)

  private val hits = VecInit((0 until nWays).map(i => t0RdRows(i).tag === t1Tag && t1Valids(i)))
  private val hit = hits.asUInt.orR
  private val hitWay = OHToUInt(hits)

  private val rdLine = t0RdRows(hitWay).data

  private val wordOff =
    if (offsetBits > log2Ceil(dataWidth / 8))
      t0Req.bits.addr(offsetBits - 1, log2Ceil(dataWidth / 8))
    else 0.U

  private val rdWord = (rdLine >> (wordOff * dataWidth.U))(dataWidth - 1, 0)

  // ── Top-side IO ──
  io.top.req.ready := state === sIdle && !t1Resp.valid
  io.top.resp.valid := t1Resp.valid
  io.top.resp.bits := t1Resp.bits

  // ── Pipeline advance ──
  when(io.top.req.fire) {
    t0Req.valid := true.B
    t0Req.bits.addr := io.top.req.bits.addr
    t0Req.bits.user := io.top.req.bits.user
  }

  when(t0Req.valid && hit) {
    t1Resp.valid := true.B
    t1Resp.bits.data := rdWord
    t1Resp.bits.user := t0Req.bits.user
  }.otherwise {
    t1Resp.valid := false.B
  }

  when(t0Req.valid && hit) { t0Req.valid := false.B }
  when(t1Resp.valid && io.top.resp.ready) { t1Resp.valid := false.B }

  // ── Bottom bus defaults ──
  io.bottom.req.valid := false.B
  io.bottom.req.bits := DontCare
  io.bottom.resp.ready := false.B
  io.bottom.resp.flush := false.B

  // ── Top bus defaults ──
  io.top.req.flush := false.B

  // ── Refill FSM ──
  switch(state) {
    is(sIdle) {
      when(t0Req.valid && !hit) {
        refillAddr := Cat(t1Tag, t1SetIdx, 0.U(offsetBits.W))
        refillSet := t1SetIdx
        refillTag := t1Tag
        refillWay := PriorityMux(
          (0 until nWays).map(i => (!t1Valids(i), i.U(wayBits.W)))
        )
        state := sRefillReq
      }
    }
    is(sRefillReq) {
      io.bottom.req.valid := true.B
      io.bottom.req.bits.addr := refillAddr
      when(io.bottom.req.ready) { state := sRefillWait }
    }
    is(sRefillWait) {
      io.bottom.resp.ready := true.B
      when(io.bottom.resp.valid) {
        // Write tag + data into SRAM, update valid
        (0 until nWays).foreach { i =>
          when(refillWay === i.U) {
            val wdata = Wire(row.cloneType)
            wdata.tag := refillTag
            wdata.data := io.bottom.resp.bits.data
            srams(i).write(refillSet, wdata)
          }
        }
        validArray(refillSet)(refillWay) := true.B
        t0Req.valid := true.B // replay
        state := sIdle
      }
    }
  }

}
