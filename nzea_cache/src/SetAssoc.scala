package nzea_cache

import chisel3._
import chisel3.util._
import nzea_rtl._

/** Simple set-associative cache with 1-cycle read latency.
  *
  *   - `nSets` × `nWays`, both must be power-of-2
  *   - top: `LiteBusRO` (CPU side, read-only)
  *   - bottom: `LiteBusRO` (memory side, refill)
  *
  * Pipeline: T0: addr → SRAM read triggered | t0Req captured T1: SRAM data arrives → tag compare → hit/miss → output
  * Miss → [[CacheRefillCtrl]] fetches full cacheline, writes to SRAM, bypasses response
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
    val top = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth, 1))
    val bottom = new LiteBusRW(addrWidth, lineBits, userWidth, 1)
  })

  // ── Submodules ──
  private val storage = Module(new CacheStorage(nSets, nWays, tagBits, lineBits))

  private val refill = Module(
    new CacheRefillCtrl(
      addrWidth,
      dataWidth,
      lineBits,
      userWidth,
      indexBits,
      tagBits,
      wayBits
    )
  )

  // ── Pipeline registers (T0 & T1) ──
  private val t0Req = RegInit(0.U.asTypeOf(Valid(new Bundle {
    val addr = UInt(addrWidth.W)
    val user = UInt(userWidth.W)
  })))

  private val t1Resp = RegInit(0.U.asTypeOf(Valid(new LiteResp(dataWidth, userWidth, 1))))

  // ═══════════════════════════════════════════════════════════════
  // T0: trigger SRAM read on new request
  // ═══════════════════════════════════════════════════════════════

  private val doRead = io.top.req.fire
  private val t0SetIdx = io.top.req.bits.addr(indexBits + offsetBits - 1, offsetBits)

  storage.io.rdSetIdx := t0SetIdx
  storage.io.rdEnable := doRead

  // ═══════════════════════════════════════════════════════════════
  // T1: tag compare + hit detection
  // ═══════════════════════════════════════════════════════════════

  private val t1SetIdx = t0Req.bits.addr(indexBits + offsetBits - 1, offsetBits)
  private val t1Tag = t0Req.bits.addr(addrWidth - 1, indexBits + offsetBits)

  storage.io.vdSetIdx := t1SetIdx

  private val hits = VecInit((0 until nWays).map { i =>
    storage.io.rdRows(i).tag === t1Tag && storage.io.vdBits(i)
  })

  private val hit = hits.asUInt.orR
  private val hitWay = OHToUInt(hits)

  private val wordOff =
    if (offsetBits > log2Ceil(dataWidth / 8))
      t0Req.bits.addr(offsetBits - 1, log2Ceil(dataWidth / 8))
    else 0.U

  private val rdWord =
    (storage.io.rdRows(hitWay).data >> (wordOff * dataWidth.U))(dataWidth - 1, 0)

  // ═══════════════════════════════════════════════════════════════
  // Miss → refill controller
  // ═══════════════════════════════════════════════════════════════

  private val doMiss = t0Req.valid && !hit

  refill.io.start := doMiss
  refill.io.startAddr := Cat(t1Tag, t1SetIdx, 0.U(offsetBits.W))
  refill.io.startSetIdx := t1SetIdx
  refill.io.startTag := t1Tag

  refill.io.startWay := PriorityMux(
    (0 until nWays).map(i => (!storage.io.vdBits(i), i.U(wayBits.W)))
  )

  refill.io.startUser := t0Req.bits.user
  refill.io.startWordOff := wordOff

  refill.io.flush := io.top.resp.flush

  io.bottom <> refill.io.bottom

  // Storage write
  storage.io.wrValid := refill.io.wrValid
  storage.io.wrSetIdx := refill.io.wrSetIdx
  storage.io.wrWay := refill.io.wrWay
  storage.io.wrTag := refill.io.wrTag
  storage.io.wrData := refill.io.wrData

  // ═══════════════════════════════════════════════════════════════
  // Top-side IO
  // ═══════════════════════════════════════════════════════════════

  io.top.req.ready := !refill.io.busy && !t1Resp.valid && !t0Req.valid
  io.top.resp.valid := t1Resp.valid
  io.top.resp.bits := t1Resp.bits

  io.top.req.flush := false.B

  // ═══════════════════════════════════════════════════════════════
  // Pipeline advance
  // ═══════════════════════════════════════════════════════════════

  // T0: capture request on fire
  when(io.top.req.fire) {
    t0Req.valid := true.B
    t0Req.bits.addr := io.top.req.bits.addr
    t0Req.bits.user := io.top.req.bits.user
  }

  // T1: on hit, form response; on refill bypass, form response; otherwise clear
  when(refill.io.bypassValid) {
    t1Resp.valid := true.B
    t1Resp.bits.data := refill.io.bypassData
    t1Resp.bits.user := refill.io.bypassUser
    t0Req.valid := false.B
  }.elsewhen(t0Req.valid && hit) {
    t1Resp.valid := true.B
    t1Resp.bits.data := rdWord
    t1Resp.bits.user := t0Req.bits.user
  }.otherwise {
    t1Resp.valid := false.B
  }

  // Consume
  when(t0Req.valid && hit) { t0Req.valid := false.B }
  when(t1Resp.valid && io.top.resp.ready) { t1Resp.valid := false.B }

  // ═══════════════════════════════════════════════════════════════
  // Flush: clear pipeline state
  // ═══════════════════════════════════════════════════════════════

  when(io.top.resp.flush) {
    t0Req.valid := false.B
    t1Resp.valid := false.B
  }

}
