package nzea_device.ddr3

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Cat, log2Ceil}
import org.scalatest.freespec.AnyFreeSpec
import nzea_rtl.LiteBusRW

/** Test double for the MIG native UI on the adapter's far side.
  *
  * This is a handshake stub, not a MIG behavior model: it always accepts
  * commands (app_rdy/wdf_rdy high), serves reads from an internal 128-bit
  * line after a fixed delay, and applies the wdf byte-mask on writes. It
  * validates the adapter's command/data path, slotting, RMW merge and response
  * handshake; controller/PHY timing is out of scope (Vivado sim + official
  * ddr3_model covers that).
  *
  * Storage: 16B-row indexed registers ([[NumRows]] rows) — a single 128-bit
  * line cannot model multi-row DDR (row 1 writes would alias row 0). The row
  * index comes from app_addr>>4 (16B granularity); app_addr bit3 selects the
  * 8B half within the returned 128-bit line (low half in [63:0], high in
  * [127:64]).
  *
  * Backpressure: real MIG command/write-data FIFOs assert their rdy only
  * when they have room. [[busyEveryN]] makes the stub periodically drop
  * app_rdy/app_wdf_rdy (default 0 = always ready, matching the simple
  * handshake-only use), so the adapter's stall paths are exercised too.
  */
class MigAppStub(numRows: Int = 8, busyEveryN: Int = 0, rdLatency: Int = 3) extends Module {
  val io = IO(new Bundle {
    val mig = new MigUiIo
    /** Row storage snapshot — lets a test inspect what the stub holds. */
    val line_snapshot = Output(UInt(128.W))
    val line_snapshot_row = Output(UInt(4.W))
    /** Read-handshake state, for debugging backpressure interactions. */
    val stub_rdPending = Output(Bool())
    val stub_rdDelay = Output(UInt(8.W))
  })

  val line = RegInit(VecInit(Seq.fill(numRows)(0.U(128.W))))
  val rdDelay = RegInit(0.U(8.W))
  val rdPending = RegInit(false.B)

  // Backpressure modeled on the real MIG FIFOs: app_rdy drops after a command
  // is accepted (command FIFO busy for [[cmdBusyCycles]]), and app_wdf_rdy drops
  // after a write word is accepted (write-data FIFO busy for [[wdfBusyCycles]]).
  // This makes the adapter hold app_en until the MIG actually samples it — the
  // real MIG's app_en pipeline (`use_addr = app_en_r2 && app_rdy`) requires
  // app_en to stay high across a potential app_rdy low.
  val cmdBusy = RegInit(false.B)
  val cmdBusyCnt = RegInit(0.U(8.W))
  val wdfBusy = RegInit(false.B)
  val wdfBusyCnt = RegInit(0.U(8.W))
  // Periodic idle backpressure too (busyEveryN=0 disables).
  val bpCnt = RegInit(0.U(8.W))
  val bpActive = if (busyEveryN > 0) { bpCnt === (busyEveryN - 1).U } else { false.B }
  if (busyEveryN > 0) {
    bpCnt := Mux(bpActive, 0.U, bpCnt + 1.U)
  }

  io.mig.app_rdy := !bpActive && !cmdBusy
  io.mig.app_wdf_rdy := !bpActive && !wdfBusy
  when(io.mig.app_en && io.mig.app_rdy && !bpActive && !cmdBusy) {
    cmdBusy := true.B
    cmdBusyCnt := 3.U
  }
  when(cmdBusy) {
    cmdBusyCnt := cmdBusyCnt - 1.U
    when(cmdBusyCnt === 1.U) { cmdBusy := false.B }
  }
  when(io.mig.app_wdf_wren && io.mig.app_wdf_rdy && !bpActive && !wdfBusy) {
    wdfBusy := true.B
    wdfBusyCnt := 2.U
  }
  when(wdfBusy) {
    wdfBusyCnt := wdfBusyCnt - 1.U
    when(wdfBusyCnt === 1.U) { wdfBusy := false.B }
  }
  io.mig.calib_done := true.B
  io.mig.ui_clk := clock
  // Match the real subsystem: the ui domain is reset until calibration
  // completes (and during the global reset). Keeps RegInit values loaded in
  // simulation (svsim has no `initial` guarantee for reset-less regs).
  io.mig.ui_rst := reset.asBool || !io.mig.calib_done
  io.mig.app_rd_data := 0.U
  io.mig.app_rd_data_valid := false.B
  // Row of the CURRENT app_addr (captured when a read/write is in flight).
  val rowSel = Wire(UInt(log2Ceil(numRows).W))
  rowSel := io.mig.app_addr(log2Ceil(numRows) + 3, 4)
  io.line_snapshot := line(rowSel)
  io.line_snapshot_row := rowSel
  io.stub_rdPending := rdPending
  io.stub_rdDelay := rdDelay
  io.stub_rdPending := rdPending
  io.stub_rdDelay := rdDelay

  val lastRow = RegInit(0.U(log2Ceil(numRows).W))
  when(io.mig.app_en && io.mig.app_rdy && io.mig.app_cmd === "b001".U) {
    rdPending := true.B
    rdDelay := rdLatency.U
    lastRow := rowSel
  }
  when(rdPending) {
    rdDelay := rdDelay - 1.U
    when(rdDelay === 1.U) {
      io.mig.app_rd_data := line(lastRow)
      io.mig.app_rd_data_valid := true.B
      rdPending := false.B
    }
  }
  // Write-data buffer: each app_wdf_data is a full 128-bit line word written
  // in ONE wren beat (MIG UI data bus is 128-bit; nCK_PER_CLK=2 is internal).
  // wdf_mask is applied per byte.
  when(io.mig.app_wdf_wren && io.mig.app_wdf_rdy) {
    val merged = VecInit.tabulate(16)(b =>
      Mux(!io.mig.app_wdf_mask(b), io.mig.app_wdf_data(8 * b + 7, 8 * b), line(lastRow)(8 * b + 7, 8 * b))
    )
    line(lastRow) := Cat(merged(15), merged(14), merged(13), merged(12),
                         merged(11), merged(10), merged(9), merged(8),
                         merged(7), merged(6), merged(5), merged(4),
                         merged(3), merged(2), merged(1), merged(0))
  }
}

/** Stub with independent calibration control: `calib_done` is an external
  * input (default high) instead of hard-wired, so a test can fire a bus
  * request while the ui domain is still in reset (MIG calibrating) and then
  * release it — reproducing the board-level race where the CPU races ahead
  * of DDR calibration (extRamCalibDone for SRAM is hard-wired high).
  */
class MigAppStubWithCalib extends Module {
  val io = IO(new Bundle {
    val mig = new MigUiIo
    val calib_done = Input(Bool())
  })

  val line = RegInit(0.U(128.W))
  val rdDelay = RegInit(0.U(4.W))
  val rdPending = RegInit(false.B)

  io.mig.app_rdy := true.B
  io.mig.app_wdf_rdy := true.B
  io.mig.calib_done := io.calib_done
  io.mig.ui_clk := clock
  io.mig.ui_rst := reset.asBool || !io.calib_done
  io.mig.app_rd_data := 0.U
  io.mig.app_rd_data_valid := false.B

  when(io.mig.app_en && io.mig.app_rdy && io.mig.app_cmd === "b001".U) {
    rdPending := true.B
    rdDelay := 3.U
  }
  when(rdPending) {
    rdDelay := rdDelay - 1.U
    when(rdDelay === 1.U) {
      io.mig.app_rd_data := line
      io.mig.app_rd_data_valid := true.B
      rdPending := false.B
    }
  }
  // Write-data buffer: one 128-bit line word per wren beat (mask applied).
  when(io.mig.app_wdf_wren && io.mig.app_wdf_rdy) {
    val merged = VecInit.tabulate(16)(b =>
      Mux(!io.mig.app_wdf_mask(b), io.mig.app_wdf_data(8 * b + 7, 8 * b), line(8 * b + 7, 8 * b))
    )
    line := Cat(merged(15), merged(14), merged(13), merged(12),
                merged(11), merged(10), merged(9), merged(8),
                merged(7), merged(6), merged(5), merged(4),
                merged(3), merged(2), merged(1), merged(0))
  }
}

/** DUT with externally-controlled calibration: adapter + stub, calib exposed. */
class Ddr3AdapterCalibTop extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(32, 32, 8, 4))
    val calib_done = Input(Bool())
  })
  val adapter = Module(new Ddr3Adapter(32, 32, 8, 4, baseAddr = BigInt("88000000", 16)))
  val stub = Module(new MigAppStubWithCalib)
  adapter.io.mig <> stub.io.mig
  adapter.io.tileReset := false.B
  stub.io.calib_done := io.calib_done
  io.bus <> adapter.io.bus
}

/** Stub variant that swallows the first `swallowReads` READ commands (never
  * returns app_rd_data_valid) — reproducing the board-level MIG read-path
  * loss. The adapter's timeout+retry must re-issue the READ and complete.
  */
class MigAppStubSwallow(swallowReads: Int) extends Module {
  val io = IO(new Bundle {
    val mig = new MigUiIo
  })

  val line = RegInit(0.U(128.W))
  val rdDelay = RegInit(0.U(4.W))
  val rdPending = RegInit(false.B)
  val readsIssued = RegInit(0.U(4.W))
  val swallowed = RegInit(0.U(4.W))
  // Command FIFO occupancy: after accepting one READ the MIG drops app_rdy for
  // a few cycles, so the adapter's (held-high) app_en does not re-issue.
  val cmdBusy = RegInit(false.B)
  val cmdBusyCnt = RegInit(0.U(8.W))

  io.mig.app_rdy := !cmdBusy
  io.mig.app_wdf_rdy := true.B
  io.mig.calib_done := true.B
  io.mig.ui_clk := clock
  io.mig.ui_rst := reset.asBool || !io.mig.calib_done
  io.mig.app_rd_data := 0.U
  io.mig.app_rd_data_valid := false.B

  when(io.mig.app_en && io.mig.app_rdy && io.mig.app_cmd === "b001".U) {
    readsIssued := readsIssued + 1.U
    cmdBusy := true.B
    cmdBusyCnt := 3.U
    when(readsIssued < swallowReads.U) {
      swallowed := swallowed + 1.U
    }.otherwise {
      rdPending := true.B
      rdDelay := 3.U
    }
  }
  when(cmdBusy) {
    cmdBusyCnt := cmdBusyCnt - 1.U
    when(cmdBusyCnt === 1.U) { cmdBusy := false.B }
  }
  when(rdPending) {
    rdDelay := rdDelay - 1.U
    when(rdDelay === 1.U) {
      io.mig.app_rd_data := line
      io.mig.app_rd_data_valid := true.B
      rdPending := false.B
    }
  }
  // Write-data buffer: one 128-bit line word per wren beat (mask applied).
  when(io.mig.app_wdf_wren && io.mig.app_wdf_rdy) {
    val merged = VecInit.tabulate(16)(b =>
      Mux(!io.mig.app_wdf_mask(b), io.mig.app_wdf_data(8 * b + 7, 8 * b), line(8 * b + 7, 8 * b))
    )
    line := Cat(merged(15), merged(14), merged(13), merged(12),
                merged(11), merged(10), merged(9), merged(8),
                merged(7), merged(6), merged(5), merged(4),
                merged(3), merged(2), merged(1), merged(0))
  }
}

/** DUT with a read-swallowing stub. */
class Ddr3AdapterSwallowTop(swallowReads: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(32, 32, 8, 4))
  })
  val adapter = Module(new Ddr3Adapter(32, 32, 8, 4, baseAddr = BigInt("88000000", 16)))
  val stub = Module(new MigAppStubSwallow(swallowReads))
  adapter.io.mig <> stub.io.mig
  adapter.io.tileReset := false.B
  io.bus <> adapter.io.bus
}

/** Stub that holds READ data indefinitely until `release_read` is asserted —
  * lets a test park the adapter's ui FSM in sRdWait (the board-level MIG
  * read-path loss: a READ is accepted but app_rd_data_valid never arrives)
  * while a tile-domain reset is applied.
  */
class MigAppStubHold extends Module {
  val io = IO(new Bundle {
    val mig = new MigUiIo
    val release_read = Input(Bool())
  })

  val line = RegInit(0.U(128.W))
  val rdPending = RegInit(false.B)

  io.mig.app_rdy := true.B
  io.mig.app_wdf_rdy := true.B
  io.mig.calib_done := true.B
  io.mig.ui_clk := clock
  io.mig.ui_rst := reset.asBool || !io.mig.calib_done
  io.mig.app_rd_data := line
  io.mig.app_rd_data_valid := rdPending && io.release_read

  when(io.mig.app_en && io.mig.app_rdy && io.mig.app_cmd === "b001".U) {
    rdPending := true.B
  }
  when(io.mig.app_rd_data_valid) {
    rdPending := false.B
  }
  // Write-data buffer: one 128-bit line word per wren beat (mask applied).
  when(io.mig.app_wdf_wren && io.mig.app_wdf_rdy) {
    val merged = VecInit.tabulate(16)(b =>
      Mux(!io.mig.app_wdf_mask(b), io.mig.app_wdf_data(8 * b + 7, 8 * b), line(8 * b + 7, 8 * b))
    )
    line := Cat(merged(15), merged(14), merged(13), merged(12),
                merged(11), merged(10), merged(9), merged(8),
                merged(7), merged(6), merged(5), merged(4),
                merged(3), merged(2), merged(1), merged(0))
  }
}

/** DUT with INDEPENDENT tile-domain and ui-domain resets — reproduces the
  * board reset split: `probe reset`/boot-load reset the tile domain
  * (cpuReset) but NOT the MIG ui domain (`ui_rst` only follows the MIG's own
  * reset/calibration). The adapter's tile side lives under `tileReset`; the
  * stub (which drives `ui_rst`) lives under `uiReset`.
  */
class Ddr3AdapterUiResetTop extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(32, 32, 8, 4))
    val tileReset = Input(Bool())
    val uiReset = Input(Bool())
    val release_read = Input(Bool())
  })
  val adapter = withReset(io.tileReset) {
    Module(new Ddr3Adapter(32, 32, 8, 4, baseAddr = BigInt("88000000", 16)))
  }
  val stub = withReset(io.uiReset) { Module(new MigAppStubHold) }
  adapter.io.mig <> stub.io.mig
  adapter.io.tileReset := io.tileReset
  stub.io.release_read := io.release_read
  io.bus <> adapter.io.bus
}

/** DUT: Ddr3Adapter + MigAppStub, with the LiteBus slave port exposed. */
class Ddr3AdapterTestTop extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(32, 32, 8, 4))
    val debug = Output(new Ddr3AdapterDebug)
    val line_snapshot = Output(UInt(128.W))
    val line_snapshot_row = Output(UInt(4.W))
  })
  val adapter = Module(new Ddr3Adapter(32, 32, 8, 4, baseAddr = BigInt("88000000", 16)))
  val stub = Module(new MigAppStub)
  adapter.io.mig <> stub.io.mig
  adapter.io.tileReset := false.B
  io.bus <> adapter.io.bus
  io.debug <> adapter.io.debug
  io.line_snapshot := stub.io.line_snapshot
  io.line_snapshot_row := stub.io.line_snapshot_row
}

/** DUT with a stub that periodically backpressures (command/write-data FIFOs
  * full every N cycles) — exercises the adapter's stall paths.
  */
class Ddr3AdapterBackpressureTop(busyEveryN: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(32, 32, 8, 4))
    val debug = Output(new Ddr3AdapterDebug)
    val stub_rdPending = Output(Bool())
    val stub_rdDelay = Output(UInt(8.W))
  })
  val adapter = Module(new Ddr3Adapter(32, 32, 8, 4, baseAddr = BigInt("88000000", 16)))
  val stub = Module(new MigAppStub(busyEveryN = busyEveryN))
  adapter.io.mig <> stub.io.mig
  adapter.io.tileReset := false.B
  io.bus <> adapter.io.bus
  io.debug <> adapter.io.debug
  io.stub_rdPending := stub.io.stub_rdPending
  io.stub_rdDelay := stub.io.stub_rdDelay
}

/** DUT with a post-write drain window (MIG write-FIFO drain before retire). */
class Ddr3AdapterDrainTop(wrDrainCycles: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(32, 32, 8, 4))
    val debug = new Ddr3AdapterDebug
  })
  val adapter = Module(new Ddr3Adapter(32, 32, 8, 4, baseAddr = BigInt("88000000", 16), wrDrainCycles = wrDrainCycles))
  val stub = Module(new MigAppStub)
  adapter.io.mig <> stub.io.mig
  adapter.io.tileReset := false.B
  io.bus <> adapter.io.bus
  io.debug <> adapter.io.debug
}

class Ddr3AdapterTest extends AnyFreeSpec with ChiselSim {

  "read-modify-write merges the wstrb bytes into the addressed 16B slot" in {
    simulate(new Ddr3AdapterTestTop) { dut =>
      // Issue one bus request and wait for its response. req.valid is pulsed:
      // after the response is observed it is de-asserted for a few cycles so
      // the next request's payload is captured fresh (a permanently-high
      // req.valid would re-fire with stale payload capture).
      def request(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(wen)
        dut.io.bus.req.bits.addr.poke(addr)
        dut.io.bus.req.bits.wdata.poke(wdata)
        dut.io.bus.req.bits.wstrb.poke(wstrb)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        dut.io.bus.resp.ready.poke(true.B)
        dut.io.bus.resp.flush.poke(false.B)
        var cycles = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 300, "request never got a response")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        dut.io.bus.req.valid.poke(false.B)
        dut.clock.step(6)
        data
      }

      // Write a full word at 0x88000010 (slot 0 of 16B line 1), read it back.
      request(wen = true, addr = 0x88000010L, wdata = 0xAABBCCDDL, wstrb = 0xF)
      val got = request(wen = false, addr = 0x88000010L, wdata = 0, wstrb = 0)
      assert(got == BigInt(0xAABBCCDDL), s"read-back mismatch: got 0x${got.toString(16)}")
    }
  }

  "byte enables keep un-written bytes of the line intact" in {
    simulate(new Ddr3AdapterTestTop) { dut =>
      def request(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(wen)
        dut.io.bus.req.bits.addr.poke(addr)
        dut.io.bus.req.bits.wdata.poke(wdata)
        dut.io.bus.req.bits.wstrb.poke(wstrb)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        dut.io.bus.resp.ready.poke(true.B)
        dut.io.bus.resp.flush.poke(false.B)
        var cycles = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 300, "request never got a response")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        dut.io.bus.req.valid.poke(false.B)
        dut.clock.step(6)
        data
      }

      // Seed line 2 (0x88000020) with a known word.
      request(wen = true, addr = 0x88000020L, wdata = 0xDEADBEEFL, wstrb = 0xF)
      // Partial write: only the low byte.
      request(wen = true, addr = 0x88000020L, wdata = 0x000000FFL, wstrb = 0x1)
      // Read back: high 3 bytes preserved, low byte replaced.
      val got = request(wen = false, addr = 0x88000020L, wdata = 0, wstrb = 0)
      assert(got == BigInt(0xDEADBEFFL), s"byte-merge mismatch: got 0x${got.toString(16)}")
    }
  }

  "request fired before calibration completes must complete after ui reset release" in {
    simulate(new Ddr3AdapterCalibTop) { dut =>
      dut.io.bus.resp.ready.poke(true.B)
      dut.io.bus.resp.flush.poke(false.B)
      dut.io.bus.req.bits.wen.poke(true.B)
      dut.io.bus.req.bits.addr.poke(0x88000010L)
      dut.io.bus.req.bits.wdata.poke(0xCAFEBABEL)
      dut.io.bus.req.bits.wstrb.poke(0xF)
      dut.io.bus.req.bits.user.poke(0)
      dut.io.bus.req.bits.id.poke(0)
      dut.io.calib_done.poke(false.B) // MIG still calibrating: ui domain in reset
      dut.io.bus.req.valid.poke(true.B)

      // The request must be *held off* while calibration is incomplete (the
      // calibDoneSync admission gate), not accepted into a wedged handshake.
      var cycles = 0
      while (!dut.io.bus.req.ready.peek().litToBoolean && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles == 20, "req admitted before calibration completed (must be gated off)")

      // Release calibration: the held request must now fire and complete.
      dut.io.calib_done.poke(true.B)
      cycles = 0
      while (!dut.io.bus.req.ready.peek().litToBoolean && cycles < 10) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 10, "req never became ready after calibration release")
      dut.clock.step() // fire
      dut.io.bus.req.valid.poke(false.B)

      cycles = 0
      while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 300, "request never got a response after calibration release")
    }
  }

  "back-to-back requests with 2-cycle gap must both complete" in {
    simulate(new Ddr3AdapterTestTop) { dut =>
      // Simulates the LSU store stream: after a response is consumed, the next
      // request rises within 2 cycles — shorter than the ui-domain sync chain
      // round-trip (reqPending fall -> sync2 fall -> doneReq fall -> doneSync2
      // fall). The second request must still be seen by the ui domain.
      def fireAndWait(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(wen)
        dut.io.bus.req.bits.addr.poke(addr)
        dut.io.bus.req.bits.wdata.poke(wdata)
        dut.io.bus.req.bits.wstrb.poke(wstrb)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        dut.io.bus.resp.ready.poke(true.B)
        dut.io.bus.resp.flush.poke(false.B)
        var cycles = 0
        while (!dut.io.bus.req.ready.peek().litToBoolean && cycles < 300) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 300, s"req for addr 0x${addr.toHexString} never became ready")
        dut.clock.step() // fire
        dut.io.bus.req.valid.poke(false.B)
        cycles = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 300, s"addr 0x${addr.toHexString} never got a response")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        // Consume the response and re-raise the next request within 2 cycles
        // (the LSU store stream pattern that exposed the sync-chain race).
        dut.clock.step(2)
        data
      }

      fireAndWait(wen = true, addr = 0x88000000L, wdata = 0x11111111L, wstrb = 0xF)
      fireAndWait(wen = true, addr = 0x88000004L, wdata = 0x22222222L, wstrb = 0xF)
      fireAndWait(wen = true, addr = 0x88000008L, wdata = 0x33333333L, wstrb = 0xF)
      fireAndWait(wen = true, addr = 0x8800000CL, wdata = 0x44444444L, wstrb = 0xF)
      val got = fireAndWait(wen = false, addr = 0x88000000L, wdata = 0, wstrb = 0)
      assert(got == BigInt(0x11111111L), s"4th back-to-back request wedged; read-back got 0x${got.toString(16)}")
    }
  }

  "tile-domain reset while the ui FSM is mid-RMW must not wedge later requests" in {
    simulate(new Ddr3AdapterUiResetTop) { dut =>
      // Board reproduction: `probe reset`/boot-load reset the tile domain
      // (cpuReset) but NOT the MIG ui domain. If the reset lands while the ui
      // FSM is waiting in sRdWait for a swallowed READ, the ui FSM never
      // returns to sIdle and every later request is dropped (reqGoSeen is only
      // serviced from sIdle/sDone) — the first store after a uart-reset
      // deadlocks. The fix: the adapter must reset its ui domain on the tile
      // cpuReset too.
      dut.io.tileReset.poke(false.B)
      dut.io.uiReset.poke(false.B)
      dut.io.release_read.poke(false.B)
      dut.io.bus.resp.ready.poke(true.B)
      dut.io.bus.resp.flush.poke(false.B)
      dut.io.bus.req.valid.poke(false.B)
      dut.clock.step(4)

      // 1. Fire a write; the stub holds the READ so the ui FSM parks in sRdWait.
      dut.io.bus.req.valid.poke(true.B)
      dut.io.bus.req.bits.wen.poke(true.B)
      dut.io.bus.req.bits.addr.poke(0x88000000L)
      dut.io.bus.req.bits.wdata.poke(0xCAFEBABEL)
      dut.io.bus.req.bits.wstrb.poke(0xF)
      dut.io.bus.req.bits.user.poke(0)
      dut.io.bus.req.bits.id.poke(0)
      var cycles = 0
      while (!dut.io.bus.req.ready.peek().litToBoolean && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 20, "first req never became ready")
      dut.clock.step() // fire
      dut.io.bus.req.valid.poke(false.B)
      // Let the ui FSM issue the READ and reach sRdWait (stub holds the data).
      dut.clock.step(8)

      // 2. Simulate `probe reset`: tile-domain reset pulse (ui domain NOT reset).
      dut.io.tileReset.poke(true.B)
      dut.clock.step(4)
      dut.io.tileReset.poke(false.B)
      dut.clock.step(4)

      // 3. Release the held READ so the ui FSM can finish its stale RMW.
      dut.io.release_read.poke(true.B)
      // Wait out the sRdWait timeout + stale completion.
      dut.clock.step(1100)

      // 4. A NEW request must complete promptly. Fix: ui domain reset on
      //    tileReset returns the ui FSM to sIdle, so this is serviced. Bug:
      //    ui FSM is still parked from step 1/2, drops the request forever.
      dut.io.bus.req.valid.poke(true.B)
      dut.io.bus.req.bits.wen.poke(true.B)
      dut.io.bus.req.bits.addr.poke(0x88000010L)
      dut.io.bus.req.bits.wdata.poke(0x12345678L)
      dut.io.bus.req.bits.wstrb.poke(0xF)
      dut.io.bus.req.bits.user.poke(0)
      dut.io.bus.req.bits.id.poke(0)
      cycles = 0
      while (!dut.io.bus.req.ready.peek().litToBoolean && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 20, "new req never became ready after tile reset")
      dut.clock.step() // fire
      dut.io.bus.req.valid.poke(false.B)
      cycles = 0
      while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 300, "new req never got a response after tile reset — ui FSM wedged mid-RMW")
    }
  }

  "swallowed READ is retried after timeout and completes" in {
    simulate(new Ddr3AdapterSwallowTop(swallowReads = 1)) { dut =>
      dut.io.bus.req.valid.poke(true.B)
      dut.io.bus.req.bits.wen.poke(true.B)
      dut.io.bus.req.bits.addr.poke(0x88000010L)
      dut.io.bus.req.bits.wdata.poke(0x12345678L)
      dut.io.bus.req.bits.wstrb.poke(0xF)
      dut.io.bus.req.bits.user.poke(0)
      dut.io.bus.req.bits.id.poke(0)
      dut.io.bus.resp.ready.poke(true.B)
      dut.io.bus.resp.flush.poke(false.B)
      var cycles = 0
      while (!dut.io.bus.req.ready.peek().litToBoolean && cycles < 10) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 10, "req never became ready")
      dut.clock.step() // fire
      dut.io.bus.req.valid.poke(false.B)

      // The first READ is swallowed: no response until the adapter's timeout
      // (1024 cycles) retries and the second READ returns data.
      cycles = 0
      while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 3000) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 3000, "request never completed after READ retry")
      assert(cycles >= 1024, s"completed too early (${cycles} cycles) — timeout retry did not happen")
    }
  }

  "successive word writes into one 16B line preserve the earlier slots" in {
    simulate(new Ddr3AdapterTestTop) { dut =>
      def request(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(wen)
        dut.io.bus.req.bits.addr.poke(addr)
        dut.io.bus.req.bits.wdata.poke(wdata)
        dut.io.bus.req.bits.wstrb.poke(wstrb)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        dut.io.bus.resp.ready.poke(true.B)
        dut.io.bus.resp.flush.poke(false.B)
        var cycles = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 300, s"req 0x${addr.toHexString} never got a response")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        dut.io.bus.req.valid.poke(false.B)
        dut.clock.step(6)
        data
      }

      // Fill all four slots of line 0 (0x88000000..0x8800000C) one by one,
      // then read each back. The RMW merge must preserve previously written
      // slots of the same 16B line (regression for the same-cycle read-line
      // hazard: merging against the stale rdLine register).
      request(wen = true, addr = 0x88000000L, wdata = 0x11111111L, wstrb = 0xF)
      request(wen = true, addr = 0x88000004L, wdata = 0x22222222L, wstrb = 0xF)
      request(wen = true, addr = 0x88000008L, wdata = 0x33333333L, wstrb = 0xF)
      request(wen = true, addr = 0x8800000CL, wdata = 0x44444444L, wstrb = 0xF)
      assert(request(wen = false, addr = 0x88000000L, wdata = 0, wstrb = 0) == BigInt(0x11111111L))
      assert(request(wen = false, addr = 0x88000004L, wdata = 0, wstrb = 0) == BigInt(0x22222222L))
      assert(request(wen = false, addr = 0x88000008L, wdata = 0, wstrb = 0) == BigInt(0x33333333L))
      assert(request(wen = false, addr = 0x8800000CL, wdata = 0, wstrb = 0) == BigInt(0x44444444L))
    }
  }

  "successive word writes into row 0 and row 1 preserve every slot" in {
    simulate(new Ddr3AdapterTestTop) { dut =>
      def request(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(wen)
        dut.io.bus.req.bits.addr.poke(addr)
        dut.io.bus.req.bits.wdata.poke(wdata)
        dut.io.bus.req.bits.wstrb.poke(wstrb)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        dut.io.bus.resp.ready.poke(true.B)
        dut.io.bus.resp.flush.poke(false.B)
        var cycles = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 300, s"req 0x${addr.toHexString} never got a response")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        dut.io.bus.req.valid.poke(false.B)
        dut.clock.step(6)
        data
      }

      // Back-to-back writes across the row-0/row-1 boundary (0x88000010 is a
      // new 16B row). Each write must land in its own address — a row-shift
      // bug (board-level "reads back the previous row") shows up here.
      dut.io.bus.resp.ready.poke(true.B)
      dut.io.bus.resp.flush.poke(false.B)
      val vals = Seq(0x11111111L, 0x22222222L, 0x33333333L, 0x44444444L,
                     0x55555555L, 0x66666666L, 0x77777777L, 0x88888888L)
      for (i <- vals.indices) {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(true.B)
        dut.io.bus.req.bits.addr.poke(0x88000000L + 4 * i)
        dut.io.bus.req.bits.wdata.poke(vals(i))
        dut.io.bus.req.bits.wstrb.poke(0xF)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        var c = 0
        while (!(dut.io.bus.req.ready.peek().litToBoolean && dut.io.bus.req.valid.peek().litToBoolean) && c < 300) {
          dut.clock.step(); c += 1
        }
        assert(c < 300, s"write $i never fired")
        // Wait for the READ command (sRdCmd, state 1) and log appAddr.
        c = 0
        while (!(dut.io.debug.uiState.peek().litValue == 1) && c < 300) {
          dut.clock.step(); c += 1
        }
        val rdUa = dut.io.debug.uAddrFull.peek().litValue
        val rdAa = dut.io.debug.appAddrFull.peek().litValue
        println(s"[write#$i] addr=0x${(0x88000000L + 4 * i).toHexString} READ:uAddr=0x${rdUa.toString(16)} appAddr=0x${rdAa.toString(16)}")
        c = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && c < 300) {
          dut.clock.step(); c += 1
        }
        assert(c < 300, s"write $i never got a response")
        dut.io.bus.req.valid.poke(false.B)
        dut.clock.step(6)
        // Log the handshake state before the next request fires.
        val hs = dut.io.debug.handshakeInProg.peek().litValue
        val rp = dut.io.debug.reqPending.peek().litValue
        val rv = dut.io.debug.respValid.peek().litValue
        val ln = dut.io.line_snapshot.peek().litValue
        println(f"[write#$i] addr=0x${0x88000000L + 4 * i}%08x line=0x$ln%032x hs=$hs rp=$rp rv=$rv")
      }
      // READ-BACK phase, with per-cycle uAddr/appAddr logging — the row-shift
      // shows up here (a read of 0x88000000 that drives appAddr=0x10 returns
      // row-1 data).
      for (i <- vals.indices) {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(false.B)
        dut.io.bus.req.bits.addr.poke(0x88000000L + 4 * i)
        dut.io.bus.req.bits.wdata.poke(0)
        dut.io.bus.req.bits.wstrb.poke(0)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        var c = 0
        while (!(dut.io.bus.req.ready.peek().litToBoolean && dut.io.bus.req.valid.peek().litToBoolean) && c < 300) {
          dut.clock.step(); c += 1
        }
        assert(c < 300, s"read $i never fired")
        // Wait until the ui FSM drives a READ command (uiState == sRdCmd, 1)
        // and log uAddr/appAddr then.
        c = 0
        while (!(dut.io.debug.uiState.peek().litValue == 1) && c < 300) {
          dut.clock.step(); c += 1
        }
        val ua = dut.io.debug.uAddrFull.peek().litValue
        val aa = dut.io.debug.appAddrFull.peek().litValue
        println(s"[read#$i] addr=0x${(0x88000000L + 4 * i).toHexString} uAddr=0x${ua.toString(16)} appAddr=0x${aa.toString(16)}")
        // sample appAddr every cycle of sRdCmd/sRdWait (stays in sRdCmd only 1
        // cycle when app_rdy is high — log both the capture-time value and the
        // value held during sRdWait, which the MIG actually sees)
        c = 0
        var appAddrSeen = -1L
        var uAddrSeen = -1L
        while (!(dut.io.debug.uiState.peek().litValue == 2) && c < 300) {
          // state 2 = sRdWait: appAddr is being held for the MIG
          dut.clock.step(); c += 1
        }
        appAddrSeen = dut.io.debug.appAddrFull.peek().litValue.toLong
        uAddrSeen = dut.io.debug.uAddrFull.peek().litValue.toLong
        println(s"[read#$i] in-sRdWait uAddr=0x${uAddrSeen.toHexString} appAddr=0x${appAddrSeen.toHexString}")
        c = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && c < 300) {
          dut.clock.step(); c += 1
        }
        assert(c < 300, s"read $i never got a response")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        println(s"[read#$i] addr=0x${(0x88000000L + 4 * i).toHexString} DATA=0x${data.toString(16)}")
        assert(data == BigInt(vals(i)), s"slot ${i} (addr 0x${(0x88000000L + 4 * i).toHexString}) read-back 0x${data.toString(16)} != 0x${vals(i).toHexString}")
        dut.io.bus.req.valid.poke(false.B)
        dut.clock.step(6)
      }
    }
  }

  "back-to-back writes capture the correct uAddr per request (diag)" in {
    simulate(new Ddr3AdapterTestTop) { dut =>
      // Fire 8 writes back-to-back, logging the captured uAddr and driven
      // appAddr on every reqFire / READ-command cycle. Expected: uAddr tracks
      // the request address 0x88000000 + 4i; appAddr = row<<4 | half<<3.
      var fireCount = 0
      def stepLog(): Unit = {
        val fire = dut.io.bus.req.ready.peek().litToBoolean && dut.io.bus.req.valid.peek().litToBoolean
        val ua = dut.io.debug.uAddrFull.peek().litValue
        val aa = dut.io.debug.appAddrFull.peek().litValue
        val st = dut.io.debug.uiState.peek().litValue
        if (fire) { fireCount += 1; println(s"[fire#$fireCount] uAddr=0x${ua.toString(16)} appAddr=0x${aa.toString(16)} uiState=$st") }
      }
      dut.io.bus.resp.ready.poke(true.B)
      dut.io.bus.resp.flush.poke(false.B)
      dut.io.bus.req.valid.poke(false.B)
      dut.clock.step(4)
      val vals = Seq(0x11111111L, 0x22222222L, 0x33333333L, 0x44444444L,
                     0x55555555L, 0x66666666L, 0x77777777L, 0x88888888L)
      for (i <- vals.indices) {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(true.B)
        dut.io.bus.req.bits.addr.poke(0x88000000L + 4 * i)
        dut.io.bus.req.bits.wdata.poke(vals(i))
        dut.io.bus.req.bits.wstrb.poke(0xF)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        // wait for fire
        var c = 0
        while (!(dut.io.bus.req.ready.peek().litToBoolean && dut.io.bus.req.valid.peek().litToBoolean) && c < 300) {
          dut.clock.step(); stepLog(); c += 1
        }
        assert(c < 300, s"req $i never fired")
        stepLog()
        dut.clock.step() // fire completes
        dut.io.bus.req.valid.poke(false.B)
        // wait for response
        c = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && c < 300) {
          dut.clock.step(); stepLog(); c += 1
        }
        assert(c < 300, s"req $i never got a response")
        dut.clock.step(3) // consume
      }
    }
  }

  "requests complete correctly under periodic MIG backpressure" in {
    simulate(new Ddr3AdapterBackpressureTop(busyEveryN = 4)) { dut =>
      // The stub drops app_rdy/app_wdf_rdy 1 cycle in every 4. The adapter must
      // stall in sRdCmd (wait for app_rdy) and sWrCmd (wait for both rdys)
      // without losing the request or corrupting the address/data.
      def request(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(wen)
        dut.io.bus.req.bits.addr.poke(addr)
        dut.io.bus.req.bits.wdata.poke(wdata)
        dut.io.bus.req.bits.wstrb.poke(wstrb)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        dut.io.bus.resp.ready.poke(true.B)
        dut.io.bus.resp.flush.poke(false.B)
        var cycles = 0
        while (!dut.io.bus.req.ready.peek().litToBoolean && cycles < 300) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 300, s"req 0x${addr.toHexString} never became ready")
        dut.clock.step() // fire
        dut.io.bus.req.valid.poke(false.B)
        cycles = 0
        var lastSt = -1L
        var lastRdp = false
        var lastRdd = -1L
        while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
          lastSt = dut.io.debug.uiState.peek().litValue.toLong
          lastRdp = dut.io.stub_rdPending.peek().litToBoolean
          lastRdd = dut.io.stub_rdDelay.peek().litValue.toLong
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 300,
          s"req 0x${addr.toHexString} never got a response under backpressure (last uiState=$lastSt stub.rdPending=$lastRdp stub.rdDelay=$lastRdd)")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        dut.clock.step(6)
        data
      }

      // Write 8 words across 2 rows, then read them all back.
      val vals = Seq(0x11111111L, 0x22222222L, 0x33333333L, 0x44444444L,
                     0x55555555L, 0x66666666L, 0x77777777L, 0x88888888L)
      for (i <- vals.indices) {
        request(wen = true, addr = 0x88000000L + 4 * i, wdata = vals(i), wstrb = 0xF)
      }
      for (i <- vals.indices) {
        val got = request(wen = false, addr = 0x88000000L + 4 * i, wdata = 0, wstrb = 0)
        assert(got == BigInt(vals(i)),
          s"under backpressure slot ${i} (addr 0x${(0x88000000L + 4 * i).toHexString}) read-back 0x${got.toString(16)} != 0x${vals(i).toHexString}")
      }
    }
  }

  "partial writes (sb/sh via wstrb) merge only the strobed bytes" in {
    simulate(new Ddr3AdapterTestTop) { dut =>
      def request(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(wen)
        dut.io.bus.req.bits.addr.poke(addr)
        dut.io.bus.req.bits.wdata.poke(wdata)
        dut.io.bus.req.bits.wstrb.poke(wstrb)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        dut.io.bus.resp.ready.poke(true.B)
        dut.io.bus.resp.flush.poke(false.B)
        var cycles = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 300, s"req 0x${addr.toHexString} never got a response")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        dut.io.bus.req.valid.poke(false.B)
        dut.clock.step(6)
        data
      }

      // Seed 0x88000010 with 0xDEADBEEF (full word).
      request(wen = true, addr = 0x88000010L, wdata = 0xDEADBEEFL, wstrb = 0xF)
      // Byte store: replace byte 1 only (0xDEAD_00EF... wait — sb writes the
      // LOW byte; to target byte index 1 we use a 32-bit store with wstrb=0b0010.
      request(wen = true, addr = 0x88000011L, wdata = 0x0000AA00L, wstrb = 0x2)
      // Read back: byte 1 = 0xAA, others preserved.
      val got = request(wen = false, addr = 0x88000010L, wdata = 0, wstrb = 0)
      assert(got == BigInt(0xDEADAAEFL), s"byte-merge mismatch: got 0x${got.toString(16)}, want 0xDEADAAEF")

      // Half-word store: replace the low 16 bits of 0x88000014 (wstrb=0b0011).
      request(wen = true, addr = 0x88000014L, wdata = 0x5566L, wstrb = 0x3)
      val got2 = request(wen = false, addr = 0x88000014L, wdata = 0, wstrb = 0)
      assert(got2 == BigInt(0x00005566L), s"halfword merge mismatch: got 0x${got2.toString(16)}, want 0x00005566")
    }
  }

  "wrDrainCycles=0 must not enter the drain window (no underflow spin)" in {
    // Board default is wrDrainCycles=0, which must mean "no drain window":
    // the write retires immediately on MIG acceptance. A regression here (the
    // counter underflowing from 0 to 255 and spinning 257 cycles in sWrCmd,
    // with appEn held the whole time) re-creates the duplicate-WRITE sampling
    // hazard that caused intermittent swallowed writes on the board.
    simulate(new Ddr3AdapterDrainTop(wrDrainCycles = 0)) { dut =>
      dut.io.bus.req.valid.poke(true.B)
      dut.io.bus.req.bits.wen.poke(true.B)
      dut.io.bus.req.bits.addr.poke(0x88000000L)
      dut.io.bus.req.bits.wdata.poke(0xDEADBEEFL)
      dut.io.bus.req.bits.wstrb.poke(0xF)
      dut.io.bus.req.bits.user.poke(0)
      dut.io.bus.req.bits.id.poke(0)
      dut.io.bus.resp.ready.poke(true.B)
      dut.io.bus.resp.flush.poke(false.B)

      var swrCmdCycles = 0
      var gotResp = false
      var cycles = 0
      while (!gotResp && cycles < 600) {
        dut.clock.step()
        cycles += 1
        if (dut.io.debug.uiState.peek().litValue.toInt == 3) swrCmdCycles += 1
        gotResp = dut.io.bus.resp.valid.peek().litToBoolean
      }
      assert(cycles < 600, "no response within 600 cycles")
      assert(
        swrCmdCycles <= 10,
        s"wrDrainCycles=0 spent $swrCmdCycles cycles in sWrCmd; expected <=10 (underflow spin regression)"
      )
    }
  }

  "stress: repeated back-to-back write+read bursts under backpressure never wedge" in {
    // Regression probe for an intermittent CDC race: the ui FSM's reqGoSeen
    // rising edge (next request) can land on the sWrCmd acceptance beat and be
    // missed by sDone, wedging the adapter. Run many short bursts so the race
    // window is hit repeatedly.
    var wedges = 0
    var completions = 0
    simulate(new Ddr3AdapterBackpressureTop(busyEveryN = 4)) { dut =>
      val vals = Seq(0x11111111L, 0x22222222L, 0x33333333L, 0x44444444L,
                     0x55555555L, 0x66666666L, 0x77777777L, 0x88888888L)
      for (iter <- 0 until 200) {
        def request(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
          dut.io.bus.req.valid.poke(true.B)
          dut.io.bus.req.bits.wen.poke(wen)
          dut.io.bus.req.bits.addr.poke(addr)
          dut.io.bus.req.bits.wdata.poke(wdata)
          dut.io.bus.req.bits.wstrb.poke(wstrb)
          dut.io.bus.req.bits.user.poke(0)
          dut.io.bus.req.bits.id.poke(0)
          dut.io.bus.resp.ready.poke(true.B)
          dut.io.bus.resp.flush.poke(false.B)
          var cycles = 0
          while (!dut.io.bus.req.ready.peek().litToBoolean && cycles < 300) {
            dut.clock.step(); cycles += 1
          }
          assert(cycles < 300, s"iter $iter req 0x${addr.toHexString} never became ready")
          dut.clock.step() // fire
          dut.io.bus.req.valid.poke(false.B)
          cycles = 0
          while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
            dut.clock.step(); cycles += 1
          }
          assert(cycles < 300,
            s"iter $iter req 0x${addr.toHexString} never got a response (last uiState=${dut.io.debug.uiState.peek().litValue})")
          val data = dut.io.bus.resp.bits.data.peek().litValue
          dut.io.bus.req.valid.poke(false.B)
          dut.clock.step(3)
          data
        }
        for (i <- vals.indices) {
          request(wen = true, addr = 0x88000000L + 4 * i, wdata = vals(i), wstrb = 0xF)
        }
        for (i <- vals.indices) {
          val got = request(wen = false, addr = 0x88000000L + 4 * i, wdata = 0, wstrb = 0)
          assert(got == BigInt(vals(i)),
            s"iter $iter slot ${i} (addr 0x${(0x88000000L + 4 * i).toHexString}) read-back 0x${got.toString(16)} != 0x${vals(i).toHexString}")
        }
        completions += 1
      }
    }
    info(s"$completions bursts completed, $wedges wedges")
  }

  "requests complete correctly with a post-write drain window" in {
    simulate(new Ddr3AdapterDrainTop(wrDrainCycles = 8)) { dut =>
      def request(wen: Boolean, addr: Long, wdata: Long, wstrb: Int): BigInt = {
        dut.io.bus.req.valid.poke(true.B)
        dut.io.bus.req.bits.wen.poke(wen)
        dut.io.bus.req.bits.addr.poke(addr)
        dut.io.bus.req.bits.wdata.poke(wdata)
        dut.io.bus.req.bits.wstrb.poke(wstrb)
        dut.io.bus.req.bits.user.poke(0)
        dut.io.bus.req.bits.id.poke(0)
        dut.io.bus.resp.ready.poke(true.B)
        dut.io.bus.resp.flush.poke(false.B)
        var cycles = 0
        while (!dut.io.bus.resp.valid.peek().litToBoolean && cycles < 300) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 300, s"req 0x${addr.toHexString} never got a response")
        val data = dut.io.bus.resp.bits.data.peek().litValue
        dut.io.bus.req.valid.poke(false.B)
        dut.clock.step(6)
        data
      }
      val vals = Seq(0x11111111L, 0x22222222L, 0x33333333L, 0x44444444L,
                     0x55555555L, 0x66666666L, 0x77777777L, 0x88888888L)
      for (i <- vals.indices) {
        request(wen = true, addr = 0x88000000L + 4 * i, wdata = vals(i), wstrb = 0xF)
      }
      for (i <- vals.indices) {
        val got = request(wen = false, addr = 0x88000000L + 4 * i, wdata = 0, wstrb = 0)
        assert(got == BigInt(vals(i)), s"drain: slot ${i} read-back 0x${got.toString(16)} != 0x${vals(i).toHexString}")
      }
    }
  }
}