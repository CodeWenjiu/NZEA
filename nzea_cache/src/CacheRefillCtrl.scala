package nzea_cache

import chisel3._
import chisel3.util._
import nzea_rtl.LiteBusRW

/** Refill controller for set-associative cache miss handling.
  *
  * FSM: idle → request → wait → write+bypass → idle.
  *
  * Reusable across I$, D$ (read-miss path), and L2 cache: connect `start` to miss detect, `bottom` to the memory-side
  * bus, `wr*` to `CacheStorage` write port, `bypass*` to the cache pipeline response.
  *
  * On flush (`io.flush`), aborts in-flight refill and drains bottom resp.
  */
class CacheRefillCtrl(
    addrWidth: Int,
    dataWidth: Int,
    lineBits: Int,
    userWidth: Int,
    indexBits: Int,
    tagBits: Int,
    wayBits: Int
) extends Module {

  private val offsetBits = log2Ceil(lineBits / 8)
  private val wordOffBits = (offsetBits - log2Ceil(dataWidth / 8)).max(0).max(1)

  val io = IO(new Bundle {
    // ── Miss trigger (pulsed) ──
    val start = Input(Bool())
    val startAddr = Input(UInt(addrWidth.W))
    val startSetIdx = Input(UInt(indexBits.W))
    val startTag = Input(UInt(tagBits.W))
    val startWay = Input(UInt(wayBits.W))
    val startUser = Input(UInt(userWidth.W))
    val startWordOff = Input(UInt(wordOffBits.W))

    // ── Status ──
    val busy = Output(Bool())

    // ── Memory-side bus ──
    val bottom = new LiteBusRW(addrWidth, lineBits, userWidth, 1)

    // ── Storage write port (asserted 1 cycle on refill completion) ──
    val wrValid = Output(Bool())
    val wrSetIdx = Output(UInt(indexBits.W))
    val wrWay = Output(UInt(wayBits.W))
    val wrTag = Output(UInt(tagBits.W))
    val wrData = Output(UInt(lineBits.W))

    // ── Bypass response (valid same cycle as wrValid) ──
    val bypassValid = Output(Bool())
    val bypassData = Output(UInt(dataWidth.W))
    val bypassUser = Output(UInt(userWidth.W))

    // ── Flush ──
    val flush = Input(Bool())
  })

  private val sIdle :: sReq :: sWait :: Nil = Enum(3)
  private val state = RegInit(sIdle)

  // ── Refill tracking registers ──
  private val refillAddr = RegInit(0.U(addrWidth.W))
  private val refillSet = RegInit(0.U(indexBits.W))
  private val refillTag = RegInit(0.U(tagBits.W))
  private val refillWay = RegInit(0.U(wayBits.W))
  private val refillUser = RegInit(0.U(userWidth.W))
  private val refillWordOff = RegInit(0.U(wordOffBits.W))

  // ── Outputs ──
  io.busy := state =/= sIdle
  io.wrValid := false.B
  io.wrSetIdx := DontCare
  io.wrWay := DontCare
  io.wrTag := DontCare
  io.wrData := DontCare
  io.bypassValid := false.B
  io.bypassData := DontCare
  io.bypassUser := DontCare

  io.bottom.req.valid := false.B
  io.bottom.req.bits := DontCare
  io.bottom.resp.ready := io.flush // drain in-flight response on flush
  io.bottom.resp.flush := false.B

  // ── FSM ──
  switch(state) {
    is(sIdle) {
      when(io.start) {
        refillAddr := io.startAddr
        refillSet := io.startSetIdx
        refillTag := io.startTag
        refillWay := io.startWay
        refillUser := io.startUser
        refillWordOff := io.startWordOff
        state := sReq
      }
    }
    is(sReq) {
      io.bottom.req.valid := true.B
      io.bottom.req.bits.addr := refillAddr
      when(io.bottom.req.ready) { state := sWait }
    }
    is(sWait) {
      io.bottom.resp.ready := true.B
      when(io.bottom.resp.valid) {
        // Storage write
        io.wrValid := true.B
        io.wrSetIdx := refillSet
        io.wrWay := refillWay
        io.wrTag := refillTag
        io.wrData := io.bottom.resp.bits.data

        // Bypass response
        io.bypassValid := true.B
        io.bypassData := (io.bottom.resp.bits.data >> (refillWordOff * dataWidth.U))(dataWidth - 1, 0)
        io.bypassUser := refillUser

        state := sIdle
      }
    }
  }

  // ── Flush ──
  when(io.flush) {
    state := sIdle
  }

}
