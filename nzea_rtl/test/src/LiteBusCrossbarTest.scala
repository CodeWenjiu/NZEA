package nzea_rtl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

class LiteBusCrossbarTest extends AnyFreeSpec with ChiselSim with LiteBusTestHelpers {

  private val ranges = Seq(
    LiteAddrRange(base = BigInt("00000000", 16), size = BigInt("00010000", 16)),
    LiteAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  )

  private def withSvsim(body: => Unit): Unit = {
    if (!hasVerilator) cancel("svsim backend unavailable: verilator not found in PATH")
    body
  }

  private class Xbar2x2Top extends Module {

    val io = IO(new Bundle {
      val in = Vec(2, Flipped(new LiteBusRW(32, 32, 8, 4)))
      val out = Vec(2, new LiteBusRW(32, 32, 8, 4))
      val decodeMiss = Output(Vec(2, Bool()))
    })

    val xbar = Module(
      new LiteBusCrossbar(
        numMasters = 2,
        addrWidth = 32,
        dataWidth = 32,
        userWidth = 8,
        idWidth = 4,
        ranges = ranges,
        perSlaveOutstanding = 8
      )
    )

    xbar.io.in <> io.in; io.out <> xbar.io.out; io.decodeMiss := xbar.io.decodeMiss
  }

  // ── compilation-only ──

  "LiteBusCrossbar elaborates" in {
    ChiselStage.emitSystemVerilog(new Xbar2x2Top)
  }

  "LiteBusArbiter elaborates" in {
    ChiselStage.emitSystemVerilog(new LiteBusArbiter(2, 32, 32, 8, 4, outstanding = 4))
  }

  // ── functional ──

  "parallel reads + writes to different slaves, plus write response" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0)); initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true); initEndpoint(dut.io.out(1), reqReady = true)
      dut.clock.step()

      // Master0 reads slave0, Master1 writes slave1.
      driveReq(dut.io.in(0), addr = BigInt("00000020", 16), user = BigInt("21", 16), id = 3)
      driveReq(
        dut.io.in(1),
        addr = BigInt("10000020", 16),
        user = BigInt("22", 16),
        id = 4,
        wen = true,
        wdata = BigInt("BEEF", 16),
        wstrb = 0xf
      )
      dut.io.out(0).req.valid.expect(true.B)
      dut.io.out(1).req.valid.expect(true.B)
      dut.io.out(1).req.bits.wen.expect(true.B)
      dut.io.out(1).req.bits.wdata.expect("hBEEF".U)
      dut.io.out(1).req.bits.wstrb.expect(0xf.U)
      dut.io.in(0).req.ready.expect(true.B)
      dut.io.in(1).req.ready.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in(0)); clearReq(dut.io.in(1))

      // Both slaves respond in same cycle: responses routed by ID.
      driveResp(dut.io.out(0), data = BigInt("AAAA", 16), user = BigInt("21", 16), id = 0)
      driveResp(dut.io.out(1), data = BigInt(0), user = BigInt("22", 16), id = 0)
      dut.io.in(0).resp.valid.expect(true.B); dut.io.in(0).resp.bits.id.expect(3.U)
      dut.io.in(1).resp.valid.expect(true.B); dut.io.in(1).resp.bits.id.expect(4.U)
      dut.clock.step()
    }
  }

  "response routing: out-of-order + two-slaves-one-master arbitration" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0)); initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true); initEndpoint(dut.io.out(1), reqReady = true)
      dut.clock.step()

      // Two requests from master0 to different slaves.
      driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt("11", 16), id = 1)
      dut.clock.step()
      driveReq(dut.io.in(0), addr = BigInt("10000010", 16), user = BigInt("22", 16), id = 2)
      dut.clock.step()
      clearReq(dut.io.in(0))

      // Slave1 responds first (out-of-order) → master0 gets id=2.
      driveResp(dut.io.out(1), data = BigInt("CCCC", 16), user = BigInt("22", 16), id = 0)
      dut.io.in(0).resp.valid.expect(true.B); dut.io.in(0).resp.bits.id.expect(2.U)
      dut.clock.step(); clearResp(dut.io.out(1))

      // Both slaves respond same cycle → crossbar arbitrates.
      driveResp(dut.io.out(0), data = BigInt("AAAA", 16), user = BigInt("11", 16), id = 0)
      driveResp(dut.io.out(1), data = BigInt("DDDD", 16), user = BigInt("33", 16), id = 0)
      dut.io.in(0).resp.valid.expect(true.B) // one delivered
      val s0Ready = dut.io.out(0).resp.ready.peek().litToBoolean
      val s1Ready = dut.io.out(1).resp.ready.peek().litToBoolean
      assert(s0Ready ^ s1Ready, s"exactly one slave ready expected, got s0=$s0Ready s1=$s1Ready")
      dut.clock.step()
      if (s0Ready) clearResp(dut.io.out(0)) else clearResp(dut.io.out(1))
      dut.io.in(0).resp.valid.expect(true.B) // second delivered next cycle
      dut.clock.step()
    }
  }

  "backpressure decoupling + queue overflow + long-stall recovery" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0), respReady = false) // stalled
      initMaster(dut.io.in(1), respReady = true) // ready
      initEndpoint(dut.io.out(0), reqReady = true); initEndpoint(dut.io.out(1), reqReady = true)
      dut.clock.step()

      // Two requests to same slave, different masters.
      driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt("11", 16), id = 1)
      dut.clock.step()
      driveReq(dut.io.in(1), addr = BigInt("00000014", 16), user = BigInt("22", 16), id = 2)
      dut.clock.step()
      clearReq(dut.io.in(0)); clearReq(dut.io.in(1))

      // Respond for master0 (stalled → queued), then for master1 (ready → bypassed).
      driveResp(dut.io.out(0), data = BigInt("AAAA", 16), user = BigInt("11", 16), id = 0)
      dut.clock.step()
      driveResp(dut.io.out(0), data = BigInt("CCCC", 16), user = BigInt("22", 16), id = 1)
      dut.io.in(1).resp.valid.expect(true.B); dut.io.in(1).resp.bits.id.expect(2.U)
      dut.io.in(1).resp.bits.user.expect("h22".U)
      dut.clock.step()

      // Release master0, drain queued response.
      dut.io.in(0).resp.ready.poke(true.B); clearResp(dut.io.out(0))
      dut.io.in(0).resp.valid.expect(true.B); dut.io.in(0).resp.bits.id.expect(1.U)
      dut.clock.step()

      // Long stall: both slaves not ready, 100 cycles.
      dut.io.out(0).req.ready.poke(false.B); dut.io.out(1).req.ready.poke(false.B)
      driveReq(dut.io.in(0), addr = BigInt("00000040", 16), user = BigInt("31", 16), id = 5)
      driveReq(dut.io.in(1), addr = BigInt("10000040", 16), user = BigInt("32", 16), id = 6)
      for (_ <- 0 until 100) {
        dut.io.in(0).req.ready.expect(false.B); dut.io.in(1).req.ready.expect(false.B)
        dut.clock.step()
      }
      // Release: both should make progress.
      dut.io.out(0).req.ready.poke(true.B); dut.io.out(1).req.ready.poke(true.B)
      dut.io.out(0).resp.valid.poke(true.B); dut.io.out(1).resp.valid.poke(true.B)
      dut.io.out(0).resp.bits.data.poke("hCD0001".U); dut.io.out(1).resp.bits.data.poke("hCD0002".U)
      dut.io.out(0).resp.bits.id.poke(0.U); dut.io.out(1).resp.bits.id.poke(0.U)
      var seen = (false, false); var c = 0
      while ((!seen._1 || !seen._2) && c < 20) {
        if (dut.io.in(0).req.ready.peek().litToBoolean) seen = (true, seen._2)
        if (dut.io.in(1).req.ready.peek().litToBoolean) seen = (seen._1, true)
        dut.clock.step(); c += 1
      }
      assert(seen._1 && seen._2, "both masters should recover after backpressure release")

      // Response queue fill: master0 stalled, 8 responses fill the queue, 9th backpressures slave.
      dut.io.in(0).resp.ready.poke(false.B)
      for (i <- 0 until 8) {
        driveReq(dut.io.in(0), addr = BigInt("00000050", 16), user = BigInt(i), id = BigInt(i))
        dut.io.in(0).req.ready.expect(true.B); dut.clock.step()
      }
      clearReq(dut.io.in(0))
      for (i <- 0 until 8) {
        driveResp(dut.io.out(0), data = BigInt(0), user = BigInt(i), id = i)
        dut.io.out(0).resp.ready.expect(true.B); dut.clock.step(); clearResp(dut.io.out(0))
      }
      // 9th request + response: queue full → slave backpressured.
      driveReq(dut.io.in(0), addr = BigInt("00000050", 16), user = BigInt(8), id = 8)
      dut.io.in(0).req.ready.expect(true.B); dut.clock.step(); clearReq(dut.io.in(0))
      driveResp(dut.io.out(0), data = BigInt(0), user = BigInt(8), id = 0)
      dut.io.out(0).resp.ready.expect(false.B)
      dut.clock.step()
    }
  }

  "per-slave outstanding slots exhausted then recovered" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0)); initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true); initEndpoint(dut.io.out(1), reqReady = true)
      dut.clock.step()

      // Fill all 8 slots.
      for (i <- 0 until 8) {
        driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt(i), id = BigInt(i))
        dut.io.in(0).req.ready.expect(true.B); dut.clock.step()
      }
      clearReq(dut.io.in(0))
      // 9th request backpressured.
      driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt(99), id = 99)
      dut.io.in(0).req.ready.expect(false.B); dut.clock.step(); clearReq(dut.io.in(0))
      // Free one slot.
      driveResp(dut.io.out(0), data = BigInt(0), user = BigInt(0), id = 0); dut.clock.step(); clearResp(dut.io.out(0))
      // 9th request now fits.
      driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt(99), id = 99)
      dut.io.in(0).req.ready.expect(true.B); dut.clock.step(); clearReq(dut.io.in(0))
    }
  }

  "decode miss returns zero data with original user + id" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0)); initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true); initEndpoint(dut.io.out(1), reqReady = true)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("20000020", 16), user = BigInt("5a", 16), id = BigInt("d", 16))
      dut.io.in(0).req.ready.expect(true.B)
      dut.io.decodeMiss(0).expect(true.B)
      dut.io.out(0).req.valid.expect(false.B); dut.io.out(1).req.valid.expect(false.B)
      dut.clock.step(); clearReq(dut.io.in(0))

      dut.io.in(0).resp.valid.expect(true.B)
      dut.io.in(0).resp.bits.data.expect(0.U)
      dut.io.in(0).resp.bits.user.expect("h5a".U)
      dut.io.in(0).resp.bits.id.expect("hd".U)
      dut.clock.step()
    }
  }

  "flush clears ownership and recovers with fresh traffic" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0)); initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true); initEndpoint(dut.io.out(1), reqReady = true)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000040", 16), user = BigInt("77", 16), id = 7)
      dut.io.in(0).req.ready.expect(true.B); dut.clock.step(); clearReq(dut.io.in(0))

      // Trigger flush.
      dut.io.in(0).resp.flush.poke(true.B); dut.clock.step(); dut.io.in(0).resp.flush.poke(false.B)

      // Stale response dropped, not delivered.
      driveResp(dut.io.out(0), data = BigInt("DEAD", 16), user = BigInt("77", 16), id = 0)
      dut.io.in(0).resp.valid.expect(false.B); dut.io.out(0).resp.ready.expect(true.B)
      dut.clock.step(); clearResp(dut.io.out(0))

      // Fresh request works normally.
      driveReq(dut.io.in(0), addr = BigInt("00000044", 16), user = BigInt("88", 16), id = 8)
      dut.io.in(0).req.ready.expect(true.B); dut.clock.step(); clearReq(dut.io.in(0))
      driveResp(dut.io.out(0), data = BigInt("BEEF", 16), user = BigInt("88", 16), id = 0)
      dut.io.in(0).resp.valid.expect(true.B); dut.io.in(0).resp.bits.id.expect(8.U)
      dut.clock.step()
    }
  }

  "round-robin on shared slave avoids starvation" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0)); initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true, respValid = true); initEndpoint(dut.io.out(1), reqReady = true)
      dut.clock.step()

      var firstGrantM1 = -1
      for (cycle <- 0 until 30) {
        driveReq(dut.io.in(0), addr = BigInt("00000020", 16), user = BigInt(10), id = (cycle % 8) + 1)
        if (cycle >= 4) {
          driveReq(dut.io.in(1), addr = BigInt("00000024", 16), user = BigInt(20), id = ((cycle + 3) % 8) + 1)
        } else {
          clearReq(dut.io.in(1))
        }
        if (cycle >= 4 && firstGrantM1 < 0 && dut.io.in(1).req.ready.peek().litToBoolean)
          firstGrantM1 = cycle
        dut.clock.step()
      }
      assert(firstGrantM1 >= 0, "master1 should eventually be granted on shared slave")
      assert(firstGrantM1 - 4 <= 10, s"grant delay too high: ${firstGrantM1 - 4}")
    }
  }

}
