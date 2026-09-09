package nzea_device.ddr3

import chisel3._
import chisel3.util.{is, switch, Cat, Enum}
import nzea_rtl.LiteBusRW

/** Debug observation port of [[Ddr3Adapter]] (kept out of the main IO so the
  * bus/mig interfaces stay clean). Tile-domain signals: `reqPending`, `respValid`,
  * `reqFire`, `flush`, `respReady` (`io.bus.req.ready`), `respValidOut`.
  * ui-domain signals: `uiState` (FSM state, 3 bits), `uiReqGoSeen`, `uiDoneReq`,
  * `uiAppEn`. Sampled cross-domain, a stale ui value just means the observing
  * clock is not aligned with ui_clk.
  */
class Ddr3AdapterDebug extends Bundle {
  val reqPending = Output(Bool())
  val respValid = Output(Bool())
  val reqFire = Output(Bool())
  val flush = Output(Bool())
  val respReady = Output(Bool())
  val respValidOut = Output(Bool())
  // Request-admission gates (new handshake): calibration synced into the tile
  // domain, and the completion handshake latch.
  val calibDoneSync = Output(Bool())
  val handshakeInProg = Output(Bool())
  // Captured request payload (tile side): write enable and byte strobes.
  val reqWen = Output(Bool())
  val reqWstrb = Output(UInt(4.W))
  // MIG wdf mask on the write handshake cycle (ui domain; 1 = masked out).
  val wdfMask = Output(UInt(16.W))
  // MIG wdf write-enable (high for both beats of the 128-bit write).
  val wdfWren = Output(Bool())
  val uiState = Output(UInt(3.W))
  val uiReqGoSeen = Output(Bool())
  val uiDoneReq = Output(Bool())
  val uiAppEn = Output(Bool())
  // Latched request edge in the ui domain (set on reqGoSeen, cleared when
  // sIdle/sDone consume it).
  val uiReqCapture = Output(Bool())
  // ui-domain synchronizer chain (proves the level was seen by ui_clk).
  val uiReqGoSync1 = Output(Bool())
  val uiReqGoSync2 = Output(Bool())
  // Captured request payload in the ui domain (set in sIdle when reqGoSeen
  // fires); low 8 bits of the app address.
  val uiWen = Output(Bool())
  val uiAddrLow8 = Output(UInt(8.W))
  // Captured request address (ui domain) and the app_addr actually driven to
  // the MIG (row<<4 | half<<3) — for observing the row-shift bug.
  val uAddrFull = Output(UInt(8.W))
  val appAddrFull = Output(UInt(8.W))
}

/** Bridges a 32-bit LiteBus slave port to the MIG 7-series native UI
  * (128-bit, 16-byte access granularity, 100 MHz ui_clk).
  *
  * The bus runs on the module's implicit clock (tile domain, 100 MHz); the
  * MIG `app_*` interface must be driven from `ui_clk` (same frequency, different
  * source), so all MIG-facing logic lives in a `withClockAndReset(ui_clk, ui_rst)`
  * island and crosses domains via pulse synchronizers.
  *
  * Access model (correctness first, single in-flight request):
  *   - address map: `app_addr = (busAddr - baseAddr) >> 3` — the MIG native
  *     UI for 16-bit DQ with bl8 addresses an 8-byte half-line per app
  *     command (app_addr bit0 = col[3] selects which 64-bit half of the
  *     128-bit datapath belongs to the address; the 128-bit line returned is
  *     the pair [high8B, low8B] of the two neighboring 8B halves).
  *   - access granularity: one 8-byte (64-bit) half-line. A 32-bit bus word
  *     lives in slot 0..1 of its 8B half (uAddr[3] = 1 selects the high 64
  *     bits of app_rd_data).
  *   - read: one READ command; the bus word is the addressed 32-bit slot of
  *     the selected 8B half.
  *   - write: read-modify-write — READ the 8B half, merge the wstrb bytes
  *     into the addressed slot, WRITE it back with an 8-bit byte mask.
  *
  * Request handshake: `reqFire` is latched into `reqPending` and its payload
  * registers stay stable until the ui-domain completion pulse returns, so the
  * ui domain may sample them safely after it sees the synchronized fire pulse.
  */
class Ddr3Adapter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    baseAddr: BigInt,
    // Cycles to hold in sWrCmd after the MIG accepts the write data, letting
    // the MIG's write FIFO drain into the DDR before we retire the request.
    // Board-level intermittent "write accepted but never lands" (immediate
    // read-back ok, delayed read-back stale) needs this drain window.
    wrDrainCycles: Int = 0
) extends Module {
  require(dataWidth == 32, s"Ddr3Adapter expects 32-bit data, got $dataWidth")

  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val mig = Flipped(new MigUiIo) // adapter drives MIG
    // Tile-domain reset (cpuReset / boot load / uart-reset) — must also reset
    // the ui FSM (see uiRstSync below). Driven by the board layer from
    // tileIo.debug.cpuReset; the module's own implicit reset is the board
    // power-on reset, which does NOT cover uart-reset.
    val tileReset = Input(Bool())
    // Debug observation port (board/ILA instrumentation; leaves unconnected in
    // plain usage like Ddr3TestCore). All signals are tile-domain except uiState/
    // uiReqGoSeen (ui_clk domain) — a stale sample there just means the ILA
    // clock may not be aligned with ui_clk.
    val debug = Output(new Ddr3AdapterDebug)
  })

  // ── Tile domain: request capture / response ────────────────────
  val reqPending = RegInit(false.B)
  val respValid = RegInit(false.B)
  val respData = RegInit(0.U(dataWidth.W))

  val reqAddr = RegInit(0.U(addrWidth.W))
  val reqWdata = RegInit(0.U(dataWidth.W))
  val reqWstrb = RegInit(0.U((dataWidth / 8).W))
  val reqWen = RegInit(false.B)
  val reqUser = RegInit(0.U(userWidth.W))
  val reqId = RegInit(0.U(idWidth.W))

  val flush = io.bus.resp.flush

  io.bus.req.flush := false.B
  io.bus.resp.valid := respValid && !flush
  io.bus.resp.bits.data := respData
  io.bus.resp.bits.user := reqUser
  io.bus.resp.bits.id := reqId

  when(flush) {
    reqPending := false.B
    respValid := false.B
  }

  // ══ ui domain: MIG app FSM (clocked by ui_clk) ════════════════
  // The ui domain must also reset on the TILE-domain reset (cpuReset / boot
  // load / uart-reset). The board splits resets: `probe reset` clears the tile
  // side but the MIG's ui_rst only follows the MIG's own reset/calibration.
  // If a tile reset lands while the ui FSM is mid-transaction (e.g. sRdWait
  // for a swallowed READ), the ui FSM would never return to sIdle and every
  // later request would be dropped — the board-level "first store after
  // uart-reset deadlocks" bug. Synchronize the tile reset into ui_clk and OR
  // it into the ui reset so the ui FSM is always bounced back to sIdle on a
  // tile reset.
  val uiRstSync = withClock(io.mig.ui_clk) {
    val r1 = RegInit(false.B)
    val r2 = RegInit(false.B)
    // Follow the tile-domain (uart) reset — the module's own implicit reset is
    // only the board power-on reset. `reset.asBool || io.tileReset` so a
    // power-on reset also resets the ui FSM.
    r1 := reset.asBool || io.tileReset
    r2 := r1
    r2
  }
  val uiRst = io.mig.ui_rst || uiRstSync

  val ui = withClockAndReset(io.mig.ui_clk, uiRst) {
    new {
      // Pulse synchronizer: tile request fire → this domain.
      //
      // Synchronize the *level* (reqPending), not the 1-cycle reqFire pulse: a
      // 2-flop synchronizer can drop a single-cycle pulse when ui_clk samples
      // out of phase (MMCM-generated, async to the tile clock), which would
      // leave reqPending set forever and hang the first DDR access. reqPending
      // stays high until the ui-domain done pulse is echoed back, so the level
      // is stable across the boundary. reqGoSeen detects its rising edge.
      val reqGoSync1 = RegInit(false.B)
      val reqGoSync2 = RegInit(false.B)
      // Rising edge of the (level) reqPending: sync1 leads sync2 by one ui_clk.
      val reqGoSeen = reqGoSync1 && !reqGoSync2
      reqGoSync1 := reqPending
      reqGoSync2 := reqGoSync1

      // The FSM consumes the latched request edge exactly once, in sIdle or
      // sDone (both states that capture a fresh request). Declared before the
      // reqCapture latch below so the latch's `when(captureNow)` can reference
      // it (Chisel requires declaration-before-use for non-lazy wires).
      val captureNow = WireDefault(false.B)

      // Request-capture latch: reqGoSeen is a 1-cycle pulse (sync1 leads
      // sync2 by one beat) that can land on ANY ui-clk beat (async CDC). The
      // FSM may be mid-transaction (sWrCmd acceptance beat, sRdWait, ...) when
      // the next request's rising edge arrives, and a state-local
      // `when(reqGoSeen)` in sIdle/sDone only would miss it — the pulse ends
      // before sDone is entered, the request is never captured, and the adapter
      // wedges (reqPending stays high forever, the tile side never sees a
      // response). Latch the edge into a level that is consumed by the next
      // sIdle/sDone capture instead.
      val reqCapture = RegInit(false.B)
      when(reqGoSeen) { reqCapture := true.B }
      // Consumed in sIdle/sDone when the FSM starts servicing it.
      when(captureNow) { reqCapture := false.B }

      // Captured request (payload stable on the tile side by now).
      val uAddr = RegInit(0.U(addrWidth.W))
      val uWdata = RegInit(0.U(dataWidth.W))
      val uWstrb = RegInit(0.U(4.W))
      val uWen = RegInit(false.B)

      val rdLine = RegInit(0.U(128.W))
      val wrLine = RegInit(0.U(128.W))
      val respDataU = RegInit(0.U(32.W))

      // MIG app outputs (defaults overridden by state).
      val appEn = RegInit(false.B)
      val appCmd = RegInit(0.U(3.W))
      val appAddr = RegInit(0.U(29.W))
      // wdfWren is combinational: it must pulse exactly on the cycle the MIG
      // samples the write data (app_rdy && app_wdf_rdy high). A register would
      // delay it one cycle past that acceptance window, and holding it across
      // backpressure would make the MIG enqueue duplicate write words.
      val wdfWren = WireDefault(false.B)
      val wdfData = RegInit(0.U(128.W))
      val wdfMask = RegInit(0.U(16.W))

      // Done pulse out to the tile domain.
      val doneReq = RegInit(false.B)

      // READ command acceptance latch: sRdCmd holds app_en until app_rdy
      // confirms the MIG sampled the command (one cycle after app_en is
      // visible), then this flag rolls the FSM into sRdWait without re-issuing.
      val rdCmdIssued = RegInit(false.B)

      // Post-write drain window (see wrDrainCycles).
      val wrDraining = RegInit(false.B)
      val wrDrainCnt = RegInit(0.U(8.W))
      // WRITE command sequencing: the MIG samples the command via its OWN
      // 2-stage app_en pipeline (app_en_r1/r2), and app_en_r1 only captures the
      // external app_en when the MIG's internal app_rdy_r (external app_rdy
      // delayed 1 beat) is high. If app_en pulses on the very cycle the external
      // app_rdy rises, app_rdy_r is still low that cycle and the pulse is lost —
      // the write is silently dropped (board-level intermittent write loss). So
      // wait one beat after external app_rdy is seen, THEN pulse app_en for one
      // cycle, by which time app_rdy_r is high and the command is captured.
      // app_en_r2 is held while app_rdy_r is low, so the single pulse can never
      // be lost to backpressure, and drops right after (r1 -> 0 -> r2 -> 0), so
      // exactly one WRITE command is enqueued per data word.
      val wrAppRdySeen = RegInit(false.B)
      val wrCmdPulse = RegInit(false.B)

      // Second beat of the multi-cycle write data (nCK_PER_CLK=2).
      val sIdle :: sRdCmd :: sRdWait :: sWrCmd :: sDone :: Nil = Enum(5)
      val state = RegInit(sIdle)

      val slotIdx = uAddr(2, 2) // 0..1: which 32-bit word inside the 8B half
      // MIG app_addr granularity is 8 bytes (app_addr bit3 = col[3] selects
      // the 8B half; the 128-bit datapath carries both halves: low in [63:0],
      // high in [127:64]). uAddr[3] selects the addressed half.
      val halfSel = uAddr(3)

      // Merged 16B line from a *128-bit* read: the addressed slot's bytes <-
      // wstrb ? wdata : old. Full-line merge; the mask limits the write to
      // the addressed 8B half.
      def mergedFrom(line: UInt): UInt = {
        val newBytes = Wire(Vec(16, UInt(8.W)))
        for (i <- 0 until 16) {
          val byteHalf = (i / 8).U
          val inSlot = (byteHalf === halfSel) && ((i % 8) / 4).U === slotIdx
          newBytes(i) := Mux(
            inSlot && uWstrb(i % 4),
            uWdata(8 * (i % 4) + 7, 8 * (i % 4)),
            line(8 * i + 7, 8 * i)
          )
        }
        Cat(newBytes(15), newBytes(14), newBytes(13), newBytes(12),
            newBytes(11), newBytes(10), newBytes(9), newBytes(8),
            newBytes(7), newBytes(6), newBytes(5), newBytes(4),
            newBytes(3), newBytes(2), newBytes(1), newBytes(0))
      }

      // MIG byte mask (active-high 1 = don't write): write the WHOLE 16B line
      // every time (mask = 0, like the 21_ddr3_test reference which never
      // partial-writes). The RMW merge already preserves every un-written
      // byte from the READ line, so a full-line write is correct and dodges
      // the board's unreliable partial-mask mapping.
      val wdfMaskVal = 0.U(16.W)

      // Read data word: the addressed 32-bit slot of the addressed half.
      def respWordFrom(line: UInt): UInt = {
        val sel = Mux(halfSel, line(127, 64), line(63, 0))
        (sel >> (slotIdx << 5))(31, 0)
      }

      // Defaults every cycle: app outputs inert except in their owning states.
      // wdfData/wdfMask follow the *captured* line (wrLine reg) — they are
      // updated when the read data lands (sRdWait), a cycle before sWrCmd,
      // so the MIG sees valid write data on the wdf handshake cycle.
      appEn := false.B
      appCmd := 0.U
      appAddr := appAddr // latched in sRdCmd; kept stable through sWrCmd
      wdfData := wrLine
      wdfMask := wdfMaskVal

      // Read-response timeout: the MIG native-UI read path on this board
      // intermittently swallows a READ command (app_rdy accepts it but
      // app_rd_data_valid never returns — observed at 400 MHz and 333 MHz,
      // and independent of access cadence). Re-issue the READ after a
      // timeout; the loss is probabilistic so a retry almost always
      // completes. 1024 ui_clk cycles (10.24 us) is far beyond the normal
      // read latency (~25 cycles) yet short enough to not stall the bus
      // noticeably.
      val rdTimeout = RegInit(0.U(10.W))

      switch(state) {
        is(sIdle) {
          rdTimeout := 0.U
          when(reqCapture) {
            captureNow := true.B
            uAddr := reqAddr
            uWdata := reqWdata
            uWstrb := reqWstrb
            uWen := reqWen
            // Lock appAddr at capture time (row<<4 | half<<3). Computing it in
            // sRdCmd would be sampled by the MIG one cycle late for back-to-back
            // requests (sRdCmd lasts a single cycle when app_rdy is high), so
            // the MIG would drive the PREVIOUS request's address — the board's
            // "reads back the previous row" bug.
            appAddr := (((reqAddr - baseAddr.U(addrWidth.W)) >> 4) << 4) | (reqAddr(3) << 3)
            state := sRdCmd
          }
        }
        is(sRdCmd) {
          rdTimeout := 0.U
          // READ always — writes continue as read-modify-write. app_en is a
          // register, so it becomes visible on the MIG bus one cycle after
          // entering sRdCmd; the MIG samples the command via its own 2-stage
          // app_en pipeline (`use_addr = app_en_r2 && app_rdy`). We keep
          // app_en asserted until app_rdy confirms acceptance — a single-cycle
          // app_en whose acceptance window coincides with app_rdy low would
          // permanently lose the READ under backpressure.
          appEn := true.B
          appCmd := "b001".U
          // appAddr was locked when uAddr was captured (sIdle/sDone); keep it
          // stable here so the MIG samples the current request's address.
          appAddr := appAddr
          when(io.mig.app_rdy) {
            rdCmdIssued := true.B
          }
          when(rdCmdIssued) {
            rdCmdIssued := false.B
            state := sRdWait
          }
        }
        is(sRdWait) {
          // Command already issued (rdCmdIssued). app_en is dropped here; do
          // NOT keep driving the READ — a re-asserted app_en would make the
          // MIG enqueue a duplicate command. Just wait for the read data.
          when(io.mig.app_rd_data_valid) {
            rdTimeout := 0.U
            rdLine := io.mig.app_rd_data
            when(uWen) {
              // Merge against the *current* read data, and load BOTH the
              // retained line (wrLine) and the wdf data registers in the same
              // cycle the read lands. wdfData must be valid one cycle before
              // the sWrCmd wdf handshake; updating it here (instead of in
              // sWrCmd) gives the MIG a settled value on the handshake edge.
              wrLine := mergedFrom(io.mig.app_rd_data)
              wdfData := mergedFrom(io.mig.app_rd_data)
              state := sWrCmd
            }.otherwise {
              respDataU := respWordFrom(io.mig.app_rd_data)
              doneReq := true.B
              state := sDone
            }
          }.elsewhen(rdTimeout.andR) {
            // READ was swallowed: retry the same address (payload regs are
            // still intact).
            rdTimeout := 0.U
            state := sRdCmd
          }.otherwise {
            rdTimeout := rdTimeout + 1.U
          }
        }
        is(sWrCmd) {
          // The MIG native UI data bus is 128 bits: one app_wdf_data word is a
          // full 16-byte line, written with a SINGLE wren beat (the
          // nCK_PER_CLK=2 PHY division happens INSIDE the MIG). app_wdf_end is
          // asserted with wren — the reference ddr3_wr_ctrl.v does exactly this
          // (each 128-bit word is its own burst).
          //
          // Command/data pairing (root cause of the board-level intermittent
          // write loss): (1) the MIG's app_rdy (command queue) and app_wdf_rdy
          // (write-data buffer) are NOT synchronized right after calibration —
          // wdf_rdy stays low until the MIG's internal data-RAM init
          // (ram_init_done) finishes while app_rdy is already high; keeping
          // app_en up in that window enqueues WRITE commands with no data. (2)
          // Even after wdf_rdy is ready, an app_en pulse on the exact cycle
          // external app_rdy rises is lost — the MIG captures app_en only when
          // its internal app_rdy_r (external app_rdy delayed one beat) is high,
          // and a same-cycle pulse sees app_rdy_r still low. So: keep app_en
          // LOW until wdf_rdy is ready; wait one beat after external app_rdy is
          // seen (so app_rdy_r is high); then pulse app_en AND wdfWren once.
          // Exactly one WRITE command is sampled (app_en_r2 drops right after
          // the pulse) and the data is accepted on the same beat.
          appCmd := "b000".U
          // appAddr was locked when uAddr was captured (sIdle/sDone); keep it
          // stable here so the MIG samples the current request's address.
          appAddr := appAddr
          when(!wrDraining) {
            when(io.mig.app_wdf_rdy) {
              when(!wrCmdPulse) {
                when(io.mig.app_rdy && !wrAppRdySeen) {
                  wrAppRdySeen := true.B
                }
                when(wrAppRdySeen && io.mig.app_rdy && io.mig.app_wdf_rdy) {
                  appEn := true.B
                  wdfWren := true.B
                  wrCmdPulse := true.B
                }
              }
              when(wrCmdPulse) {
                if (wrDrainCycles == 0) {
                  wrAppRdySeen := false.B
                  wrCmdPulse := false.B
                  doneReq := true.B
                  state := sDone
                } else {
                  wrDraining := true.B
                  wrDrainCnt := wrDrainCycles.U
                }
              }
            }
          }
          when(wrDraining) {
            // Drain window: app_en stays low (default) so no new WRITE is
            // enqueued; hold for wrDrainCycles beats so the MIG's write FIFO
            // drains into the DDR before we retire. If the MC is still busy
            // (refresh) the NEXT write simply waits for wdf_rdy in sWrCmd with
            // app_en low — a wait, never a deadlock (no command is issued
            // without data).
            wrDrainCnt := wrDrainCnt - 1.U
            when(wrDrainCnt === 1.U) {
              wrDraining := false.B
              wrAppRdySeen := false.B
              wrCmdPulse := false.B
              doneReq := true.B
              state := sDone
            }
          }
        }
        is(sDone) {
          // Wait for the tile domain to acknowledge (doneReq level drops once
          // reqPending is seen falling here). The next request's rising edge
          // may arrive while still in sDone (the handshake latch on the tile
          // side releases reqReady as soon as it sees doneSeen, before the ui
          // domain observes reqPending falling) — capture it here so uAddr is
          // not lost and the FSM rolls straight into the next READ. reqCapture
          // latches the edge even if it arrived mid-transaction (see above).
          when(reqCapture) {
            captureNow := true.B
            uAddr := reqAddr
            uWdata := reqWdata
            uWstrb := reqWstrb
            uWen := reqWen
            // Lock appAddr at capture time, same as sIdle (see there).
            appAddr := (((reqAddr - baseAddr.U(addrWidth.W)) >> 4) << 4) | (reqAddr(3) << 3)
            state := sRdCmd
          }.elsewhen(!doneReq) {
            state := sIdle
          }
        }
      }

      // Done is a *level* (stays high until the tile domain acknowledges by
      // dropping reqPending, seen here as reqGoSync2 falling). A 1-cycle
      // auto-clear would be dropped by the return 2-flop synchronizer the same
      // way reqFire would be.
      when(!reqGoSync2) { doneReq := false.B }
    }
  }

  // Drive MIG app outputs from the ui-domain registers.
  io.mig.app_en := ui.appEn
  io.mig.app_cmd := ui.appCmd
  io.mig.app_addr := ui.appAddr
  io.mig.app_wdf_wren := ui.wdfWren
  io.mig.app_wdf_data := ui.wdfData
  io.mig.app_wdf_mask := ui.wdfMask
  // app_wdf_end marks the LAST beat of the write-data burst. Each of our
  // writes is a single 128-bit word (one wren beat), so end = wren (every
  // beat is the last) — exactly what the reference ddr3_wr_ctrl.v does.
  io.mig.app_wdf_end := ui.wdfWren

  // ══ ui → tile: completion synchronizer ════════════════════════
  val doneSync1 = RegInit(false.B)
  val doneSync2 = RegInit(false.B)
  doneSync1 := ui.doneReq
  doneSync2 := doneSync1
  // Rising edge of the (level) doneReq: sync1 leads sync2 by one tile clk.
  val doneSeen = doneSync1 && !doneSync2

  when(doneSeen) {
    respData := ui.respDataU
    respValid := true.B
    reqPending := false.B
  }
  when(respValid && io.bus.resp.ready && !flush) { respValid := false.B }

  // ── Request admission (after the completion handshake) ─────────
  // MIG calibration level, synchronized into this (tile) domain: requests are
  // not admitted until calibration completes. A request fired while the ui
  // domain is still in reset would assert reqPending with no ui_clk edge to
  // ever sample it, wedging the level handshake permanently.
  val calibSync1 = RegInit(false.B)
  val calibSync2 = RegInit(false.B)
  calibSync1 := io.mig.calib_done
  calibSync2 := calibSync1

  // Completion handshake latch: after a done is echoed back (doneSeen), keep
  // reqReady low until the ui domain has observed the reqPending falling edge
  // (doneReq drops, seen here via doneSync2). Without this, a request raised
  // 1-2 cycles after the response would re-assert reqPending before the ui
  // sync chain sees the fall — no new rising edge is ever produced and the
  // ui FSM stays in sIdle forever (the board-level back-to-back store wedge).
  val handshakeInProg = RegInit(false.B)
  when(doneSeen) { handshakeInProg := true.B }
  when(flush || (!doneSync2 && handshakeInProg)) { handshakeInProg := false.B }

  val reqReady = !reqPending && !respValid && !flush && !handshakeInProg && calibSync2
  io.bus.req.ready := reqReady

  // Capture a request with its payload (stable until ui-domain done).
  val reqFire = io.bus.req.valid && reqReady
  when(reqFire) {
    reqAddr := io.bus.req.bits.addr
    reqWdata := io.bus.req.bits.wdata
    reqWstrb := io.bus.req.bits.wstrb
    reqWen := io.bus.req.bits.wen
    reqUser := io.bus.req.bits.user
    reqId := io.bus.req.bits.id
    reqPending := true.B
  }

  // ── Debug port ────────────────────────────────────────────────
  io.debug.reqPending := reqPending
  io.debug.respValid := respValid
  io.debug.reqFire := reqFire
  io.debug.flush := flush
  io.debug.respReady := io.bus.resp.ready
  io.debug.respValidOut := io.bus.resp.valid
  io.debug.calibDoneSync := calibSync2
  io.debug.handshakeInProg := handshakeInProg
  io.debug.reqWen := reqWen
  io.debug.reqWstrb := reqWstrb
  io.debug.wdfMask := ui.wdfMask
  io.debug.wdfWren := ui.wdfWren
  io.debug.uiState := ui.state
  io.debug.uiReqGoSeen := ui.reqGoSeen
  io.debug.uiReqCapture := ui.reqCapture
  io.debug.uiDoneReq := ui.doneReq
  io.debug.uiAppEn := ui.appEn
  io.debug.uiReqGoSync1 := ui.reqGoSync1
  io.debug.uiReqGoSync2 := ui.reqGoSync2
  io.debug.uiWen := ui.uWen
  io.debug.uiAddrLow8 := ui.uAddr(7, 0)
  io.debug.uAddrFull := ui.uAddr(7, 0)
  io.debug.appAddrFull := ui.appAddr(7, 0)
}