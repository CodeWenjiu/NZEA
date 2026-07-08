package nzea_rtl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

class FabricBusCrossbarTest extends AnyFreeSpec with ChiselSim with FabricBusTestHelpers {

  private val ranges = Seq(
    FabricAddrRange(base = BigInt("00000000", 16), size = BigInt("00010000", 16)),
    FabricAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  )

  private def withSvsim(body: => Unit): Unit = {
    if (!hasVerilator) cancel("svsim backend unavailable: verilator not found in PATH")
    body
  }

  private class Xbar2x2Top extends Module {

    val io = IO(new Bundle {
      val in = Vec(2, Flipped(new FabricBusRW(32, 32, 8, 4)))
      val out = Vec(2, new FabricBusRW(32, 32, 8, 4))
      val decodeMiss = Output(Vec(2, Bool()))
    })

    val xbar = Module(
      new FabricBusRWCrossbar(
        numMasters = 2,
        addrWidth = 32,
        dataWidth = 32,
        userWidth = 8,
        idWidth = 4,
        ranges = ranges,
        perSlaveOutstanding = 8
      )
    )

    xbar.io.in <> io.in
    io.out <> xbar.io.out
    io.decodeMiss := xbar.io.decodeMiss
  }

  "FabricBusRWCrossbar elaborates" in {
    ChiselStage.emitSystemVerilog(new Xbar2x2Top)
  }

  "FabricBusRWArbiter elaborates" in {
    ChiselStage.emitSystemVerilog(new FabricBusRWArbiter(2, 32, 32, 8, 4, outstanding = 4))
  }

  "FabricBusRWCrossbar supports multiple in-flight and out-of-order responses by id" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true, respValid = false)
      initEndpoint(dut.io.out(1), reqReady = true, respValid = false)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt("11", 16), id = 1)
      dut.io.in(0).req.ready.expect(true.B)
      dut.io.out(0).req.valid.expect(true.B)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000014", 16), user = BigInt("12", 16), id = 2)
      dut.io.in(0).req.ready.expect(true.B)
      dut.io.out(0).req.valid.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in(0))

      // slave-side ids are remapped by xbar per-slave outstanding slot: req#1->tag0, req#2->tag1.
      driveResp(dut.io.out(0), data = BigInt("ABCD0002", 16), user = BigInt("12", 16), id = 1)
      dut.io.in(0).resp.valid.expect(true.B)
      dut.io.in(0).resp.bits.id.expect(2.U)
      dut.io.in(0).resp.bits.user.expect("h12".U)
      dut.clock.step()

      driveResp(dut.io.out(0), data = BigInt("ABCD0001", 16), user = BigInt("11", 16), id = 0)
      dut.io.in(0).resp.valid.expect(true.B)
      dut.io.in(0).resp.bits.id.expect(1.U)
      dut.io.in(0).resp.bits.user.expect("h11".U)
      dut.clock.step()
    }
  }

  "FabricBusRWCrossbar routes parallel requests to different slaves" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true, respValid = false)
      initEndpoint(dut.io.out(1), reqReady = true, respValid = false)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000020", 16), user = BigInt("21", 16), id = 3)
      driveReq(dut.io.in(1), addr = BigInt("10000020", 16), user = BigInt("22", 16), id = 4)
      dut.io.out(0).req.valid.expect(true.B)
      dut.io.out(1).req.valid.expect(true.B)
      dut.io.in(0).req.ready.expect(true.B)
      dut.io.in(1).req.ready.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in(0))
      clearReq(dut.io.in(1))

      // first outstanding on each slave uses tag0.
      driveResp(dut.io.out(0), data = BigInt("AAAABBBB", 16), user = BigInt("21", 16), id = 0)
      driveResp(dut.io.out(1), data = BigInt("CCCCDDDD", 16), user = BigInt("22", 16), id = 0)
      dut.io.in(0).resp.valid.expect(true.B)
      dut.io.in(1).resp.valid.expect(true.B)
      dut.io.in(0).resp.bits.id.expect(3.U)
      dut.io.in(1).resp.bits.id.expect(4.U)
      dut.clock.step()
    }
  }

  "FabricBusRWCrossbar returns decode-miss response with original id/user and zero data" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true, respValid = false)
      initEndpoint(dut.io.out(1), reqReady = true, respValid = false)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("20000020", 16), user = BigInt("5a", 16), id = BigInt("d", 16))
      dut.io.in(0).req.ready.expect(true.B)
      dut.io.decodeMiss(0).expect(true.B)
      dut.io.out(0).req.valid.expect(false.B)
      dut.io.out(1).req.valid.expect(false.B)
      dut.clock.step()
      clearReq(dut.io.in(0))

      dut.io.in(0).resp.valid.expect(true.B)
      dut.io.in(0).resp.bits.data.expect(0.U)
      dut.io.in(0).resp.bits.user.expect("h5a".U)
      dut.io.in(0).resp.bits.id.expect("hd".U)
      dut.clock.step()
    }
  }

  "FabricBusRWCrossbar arbitrates when one master receives two slave responses in same cycle" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true, respValid = false)
      initEndpoint(dut.io.out(1), reqReady = true, respValid = false)
      dut.clock.step()

      // Two requests from same master to different slaves, both outstanding.
      driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt("11", 16), id = 1)
      dut.io.in(0).req.ready.expect(true.B)
      dut.clock.step()
      driveReq(dut.io.in(0), addr = BigInt("10000010", 16), user = BigInt("22", 16), id = 2)
      dut.io.in(0).req.ready.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in(0))

      // Both slaves return in the same cycle: xbar must pick one and backpressure the other.
      // first outstanding on each slave uses tag0.
      driveResp(dut.io.out(0), data = BigInt("AAAABBBB", 16), user = BigInt("11", 16), id = 0)
      driveResp(dut.io.out(1), data = BigInt("CCCCDDDD", 16), user = BigInt("22", 16), id = 0)

      dut.io.in(0).resp.valid.expect(true.B)
      val firstId = dut.io.in(0).resp.bits.id.peek().litValue
      assert(firstId == 1 || firstId == 2, s"unexpected first response id=$firstId")
      val s0Ready = dut.io.out(0).resp.ready.peek().litToBoolean
      val s1Ready = dut.io.out(1).resp.ready.peek().litToBoolean
      assert(s0Ready ^ s1Ready, s"expected exactly one ready, got s0=$s0Ready s1=$s1Ready")
      dut.clock.step()

      // Keep only the response that was backpressured, it should be delivered next cycle.
      if (s0Ready) clearResp(dut.io.out(0)) else clearResp(dut.io.out(1))
      dut.io.in(0).resp.valid.expect(true.B)
      val secondId = dut.io.in(0).resp.bits.id.peek().litValue
      assert(secondId != firstId, s"second response id should differ, got $secondId after $firstId")
      dut.clock.step()
      clearResp(dut.io.out(0))
      clearResp(dut.io.out(1))
    }
  }

  "FabricBusRWCrossbar decouples per-master resp backpressure on shared slave" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0), respReady = false) // stalled owner master
      initMaster(dut.io.in(1), respReady = true)
      initEndpoint(dut.io.out(0), reqReady = true, respValid = false)
      initEndpoint(dut.io.out(1), reqReady = true, respValid = false)
      dut.clock.step()

      // Two requests to the same slave from different masters.
      driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt("11", 16), id = 1)
      dut.io.in(0).req.ready.expect(true.B)
      dut.clock.step()
      driveReq(dut.io.in(1), addr = BigInt("00000014", 16), user = BigInt("22", 16), id = 2)
      dut.io.in(1).req.ready.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in(0))
      clearReq(dut.io.in(1))

      // First response targets stalled master0: xbar should absorb it.
      // req#1->tag0, req#2->tag1 on the same slave.
      driveResp(dut.io.out(0), data = BigInt("AAAABBBB", 16), user = BigInt("11", 16), id = 0)
      dut.io.out(0).resp.ready.expect(true.B)
      dut.clock.step()

      // Second response targets master1: must still be delivered even while master0 stays stalled.
      driveResp(dut.io.out(0), data = BigInt("CCCCDDDD", 16), user = BigInt("22", 16), id = 1)
      dut.io.out(0).resp.ready.expect(true.B)
      dut.io.in(1).resp.valid.expect(true.B)
      dut.io.in(1).resp.bits.id.expect(2.U)
      dut.io.in(1).resp.bits.user.expect("h22".U)
      dut.clock.step()

      // Release master0 and drain its queued response.
      dut.io.in(0).resp.ready.poke(true.B)
      clearResp(dut.io.out(0))
      dut.io.in(0).resp.valid.expect(true.B)
      dut.io.in(0).resp.bits.id.expect(1.U)
      dut.io.in(0).resp.bits.user.expect("h11".U)
      dut.clock.step()
    }
  }

  "FabricBusRWCrossbar round-robin on same slave avoids starvation" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true, respValid = true)
      initEndpoint(dut.io.out(1), reqReady = true, respValid = false)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000020", 16), user = BigInt("10", 16), id = 1)
      var firstGrantM1 = -1
      val m1Start = 6
      for (cycle <- 0 until 40) {
        driveReq(
          dut.io.in(0),
          addr = BigInt("00000020", 16),
          user = BigInt("10", 16),
          id = (cycle % 16) + 1
        )
        if (cycle >= m1Start) {
          driveReq(
            dut.io.in(1),
            addr = BigInt("00000024", 16),
            user = BigInt("20", 16),
            id = ((cycle + 7) % 16) + 1
          )
        } else {
          clearReq(dut.io.in(1))
        }
        if (cycle >= m1Start && firstGrantM1 < 0 && dut.io.in(1).req.ready.peek().litToBoolean) {
          firstGrantM1 = cycle
        }
        dut.clock.step()
      }
      assert(firstGrantM1 >= 0, "master1 should eventually be granted on shared slave")
      assert(firstGrantM1 - m1Start <= 12, s"master1 grant delay too high: ${firstGrantM1 - m1Start}")
    }
  }

  "FabricBusRWCrossbar survives 100-cycle full backpressure and recovers both masters" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = false, respValid = false)
      initEndpoint(dut.io.out(1), reqReady = false, respValid = false)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000040", 16), user = BigInt("31", 16), id = 5)
      driveReq(dut.io.in(1), addr = BigInt("10000040", 16), user = BigInt("32", 16), id = 6)
      for (_ <- 0 until 100) {
        dut.io.in(0).req.ready.expect(false.B)
        dut.io.in(1).req.ready.expect(false.B)
        dut.clock.step()
      }

      dut.io.out(0).req.ready.poke(true.B)
      dut.io.out(1).req.ready.poke(true.B)
      dut.io.out(0).resp.valid.poke(true.B)
      dut.io.out(1).resp.valid.poke(true.B)
      dut.io.out(0).resp.bits.data.poke("habcd0001".U)
      dut.io.out(1).resp.bits.data.poke("habcd0002".U)
      dut.io.out(0).resp.bits.user.poke("h31".U)
      dut.io.out(1).resp.bits.user.poke("h32".U)
      // first outstanding on each slave uses tag0.
      dut.io.out(0).resp.bits.id.poke(0.U)
      dut.io.out(1).resp.bits.id.poke(0.U)

      var seen0 = false
      var seen1 = false
      var cycles = 0
      while (!(seen0 && seen1) && cycles < 20) {
        if (dut.io.in(0).req.ready.peek().litToBoolean) seen0 = true
        if (dut.io.in(1).req.ready.peek().litToBoolean) seen1 = true
        dut.clock.step()
        cycles += 1
      }
      assert(seen0 && seen1, "both masters should make progress after backpressure release")
    }
  }

  "FabricBusRWCrossbar flush clears outstanding ownership and later recovers with new requests" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true, respValid = false)
      initEndpoint(dut.io.out(1), reqReady = true, respValid = false)
      dut.clock.step()

      // Build one outstanding transaction.
      driveReq(dut.io.in(0), addr = BigInt("00000040", 16), user = BigInt("77", 16), id = 7)
      dut.io.in(0).req.ready.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in(0))

      // Trigger global flush from master side.
      dut.io.in(0).resp.flush.poke(true.B)
      dut.clock.step()
      dut.io.in(0).resp.flush.poke(false.B)

      // Old response must be dropped after flush (no match), not delivered to master.
      driveResp(dut.io.out(0), data = BigInt("DEAD7777", 16), user = BigInt("77", 16), id = 0)
      dut.io.in(0).resp.valid.expect(false.B)
      dut.io.out(0).resp.ready.expect(true.B)
      dut.clock.step()
      clearResp(dut.io.out(0))

      // Fabric should recover and serve new traffic normally.
      driveReq(dut.io.in(0), addr = BigInt("00000044", 16), user = BigInt("88", 16), id = 8)
      dut.io.in(0).req.ready.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in(0))

      driveResp(dut.io.out(0), data = BigInt("BEEF8888", 16), user = BigInt("88", 16), id = 0)
      dut.io.in(0).resp.valid.expect(true.B)
      dut.io.in(0).resp.bits.id.expect(8.U)
      dut.io.in(0).resp.bits.user.expect("h88".U)
      dut.clock.step()
    }
  }

}
