package nzea_rtl

import chisel3._
import chisel3.util._

/** Width converter between a wide LiteBusRW and a narrow LiteBusRW.
  *
  * When `wideDataWidth == narrowDataWidth`, the converter degenerates into a
  * zero-latency wire-through — no FSM, no registers, purely `io.narrow <> io.wide`.
  *
  * When `wideDataWidth > narrowDataWidth`, one wide request is split into
  * `ratio` sequential narrow requests (burst), and `ratio` narrow responses
  * are assembled back into one wide response.
  *
  * @param wideDataWidth   data width on the wide (master) side
  * @param narrowDataWidth data width on the narrow (slave) side
  * @param addrWidth       address width (shared)
  * @param userWidth       user-field width (shared)
  * @param idWidth         id width (shared)
  */
class LiteBusWidthConverter(
    wideDataWidth: Int,
    narrowDataWidth: Int,
    addrWidth: Int,
    userWidth: Int,
    idWidth: Int
) extends Module {
  require(wideDataWidth >= narrowDataWidth,
    s"wideDataWidth=$wideDataWidth must be >= narrowDataWidth=$narrowDataWidth")
  require(
    wideDataWidth == narrowDataWidth || wideDataWidth % narrowDataWidth == 0,
    s"wideDataWidth=$wideDataWidth must be a multiple of narrowDataWidth=$narrowDataWidth"
  )

  val io = IO(new Bundle {
    val wide = Flipped(new LiteBusRW(addrWidth, wideDataWidth, userWidth, idWidth))
    val narrow = new LiteBusRW(addrWidth, narrowDataWidth, userWidth, idWidth)
  })

  // ── Zero-cost passthrough when widths match ────────────────────
  if (wideDataWidth == narrowDataWidth) {
    io.narrow <> io.wide
  } else {
    // ── Active conversion ──────────────────────────────────────
    val ratio = wideDataWidth / narrowDataWidth
    val step = narrowDataWidth / 8
    val cntBits = log2Ceil(ratio)

    val sIdle :: sReq :: sCollect :: sAssemble :: sResp :: Nil = Enum(5)
    val state = RegInit(sIdle)

    // ── Captured wide request ──────────────────────────────
    val wideAddr = RegInit(0.U(addrWidth.W))
    val wideUser = RegInit(0.U(userWidth.W))
    val wideId = RegInit(0.U(idWidth.W))
    val wideWen = RegInit(false.B)
    val wideWdata = RegInit(0.U(wideDataWidth.W))
    val wideWstrb = RegInit(0.U((wideDataWidth / 8).W))

    // ── Narrow-request counter ─────────────────────────────
    val reqCnt = RegInit(0.U(cntBits.W))

    // ── Response assembly ──────────────────────────────────
    val respBuf = RegInit(VecInit(Seq.fill(ratio)(0.U(narrowDataWidth.W))))
    val respCnt = RegInit(0.U(cntBits.W))

    // ── Wide response output register ──────────────────────
    val wideRespValid = RegInit(false.B)
    val wideRespData = RegInit(0.U(wideDataWidth.W))
    val wideRespUser = RegInit(0.U(userWidth.W))

    // ── Flush plumbing ─────────────────────────────────────
    // Forward flush bidirectionally without creating a combinational loop.
    io.narrow.resp.flush := io.wide.resp.flush
    io.wide.req.flush := io.narrow.req.flush

    // ── Defaults ───────────────────────────────────────────
    io.wide.req.ready := false.B
    io.wide.resp.valid := false.B
    io.wide.resp.bits := DontCare

    io.narrow.req.valid := false.B
    io.narrow.req.bits := DontCare
    io.narrow.resp.ready := false.B

    switch(state) {
      is(sIdle) {
        io.wide.req.ready := true.B
        io.narrow.resp.ready := true.B // drain stale responses from aborted refills
        when(io.wide.req.valid && !io.wide.resp.flush && !io.narrow.req.flush) {
          wideAddr := io.wide.req.bits.addr
          wideUser := io.wide.req.bits.user
          wideId := io.wide.req.bits.id
          wideWen := io.wide.req.bits.wen
          wideWdata := io.wide.req.bits.wdata
          wideWstrb := io.wide.req.bits.wstrb
          reqCnt := 0.U
          state := sReq
        }
      }

      is(sReq) {
        io.narrow.req.valid := true.B
        io.narrow.req.bits.addr := wideAddr + (reqCnt * step.U)
        io.narrow.req.bits.user := wideUser
        io.narrow.req.bits.id := wideId
        io.narrow.req.bits.wen := wideWen
        io.narrow.req.bits.wstrb := (wideWstrb >> (reqCnt * (narrowDataWidth / 8).U))(narrowDataWidth / 8 - 1, 0)
        io.narrow.req.bits.wdata := (wideWdata >> (reqCnt * narrowDataWidth.U))(narrowDataWidth - 1, 0)

        when(io.narrow.req.ready) {
          when(reqCnt === (ratio - 1).U) {
            reqCnt := 0.U
            respCnt := 0.U
            state := sCollect
          }.otherwise {
            reqCnt := reqCnt + 1.U
          }
        }
      }

      is(sCollect) {
        io.narrow.resp.ready := true.B
        when(io.narrow.resp.valid) {
          respBuf(respCnt) := io.narrow.resp.bits.data
          when(respCnt === (ratio - 1).U) {
            state := sAssemble
          }.otherwise {
            respCnt := respCnt + 1.U
          }
        }
      }

      is(sAssemble) {
        wideRespData := respBuf.asUInt
        wideRespUser := io.narrow.resp.bits.user
        wideRespValid := true.B
        state := sResp
      }

      is(sResp) {
        io.wide.resp.valid := wideRespValid
        io.wide.resp.bits.data := wideRespData
        io.wide.resp.bits.user := wideRespUser
        io.wide.resp.bits.id := wideId
        when(io.wide.resp.ready) {
          wideRespValid := false.B
          state := sIdle
        }
      }
    }

    when(io.wide.resp.flush || io.narrow.req.flush) {
      state := sIdle
      wideRespValid := false.B
    }
  }
}