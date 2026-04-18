package nzea_rtl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

class LiteBusArbiterTest extends AnyFreeSpec with ChiselSim with LiteBusTestHelpers {

  private case class ScheduledReq(master: Int, startCycle: Int, addr: BigInt, user: BigInt)

  private def withSvsim(body: => Unit): Unit = {
    if (!hasVerilator) cancel("svsim backend unavailable: verilator not found in PATH")
    body
  }

  private def driveScheduled(dut: LiteBusRWArbiter, cycle: Int, script: Seq[ScheduledReq]): Unit = {
    dut.io.in.indices.foreach(i => clearReq(dut.io.in(i)))
    script.filter(_.startCycle <= cycle).foreach { s =>
      driveReq(dut.io.in(s.master), addr = s.addr, user = s.user)
    }
  }

  "LiteBusRWArbiter elaborates" in {
    ChiselStage.emitSystemVerilog(new LiteBusRWArbiter(2, 32, 32, 8))
  }

  "LiteBusROToRW elaborates" in {
    ChiselStage.emitSystemVerilog(new LiteBusROToRW(32, 32, 8))
  }

  "LiteBusRWArbiter round-robin prevents starvation under heavy master0 traffic" in withSvsim {
    simulate(new LiteBusRWArbiter(2, 32, 32, 8)) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out, reqReady = true, respValid = true)
      dut.clock.step()

      val script = Seq(
        ScheduledReq(master = 0, startCycle = 0, addr = BigInt("00000010", 16), user = BigInt("10", 16)),
        ScheduledReq(master = 1, startCycle = 8, addr = BigInt("10000010", 16), user = BigInt("20", 16))
      )

      var firstGrantCycle = -1
      val startCycle = 8
      for (cycle <- 0 until 40) {
        driveScheduled(dut, cycle, script)
        if (cycle >= startCycle && firstGrantCycle < 0 && dut.io.in(1).req.ready.peek().litToBoolean) {
          firstGrantCycle = cycle
        }
        dut.clock.step()
      }

      assert(firstGrantCycle >= 0, "master1 should eventually get granted")
      assert(firstGrantCycle - startCycle <= 12, s"master1 grant latency too high: ${firstGrantCycle - startCycle} cycles")
    }
  }

  "LiteBusRWArbiter handles 100-cycle full backpressure and recovers without dropping requests" in withSvsim {
    simulate(new LiteBusRWArbiter(2, 32, 32, 8)) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out, reqReady = false, respValid = false)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000020", 16), user = BigInt("31", 16))
      driveReq(dut.io.in(1), addr = BigInt("10000020", 16), user = BigInt("32", 16))
      for (_ <- 0 until 100) {
        dut.io.in(0).req.ready.expect(false.B)
        dut.io.in(1).req.ready.expect(false.B)
        dut.clock.step()
      }

      dut.io.out.req.ready.poke(true.B)
      dut.io.out.resp.valid.poke(true.B)
      dut.io.out.resp.bits.data.poke("hCAFE0001".U)

      var seen0 = false
      var seen1 = false
      var cycles = 0
      while (!(seen0 && seen1) && cycles < 40) {
        if (dut.io.in(0).req.ready.peek().litToBoolean) seen0 = true
        if (dut.io.in(1).req.ready.peek().litToBoolean) seen1 = true
        dut.clock.step()
        cycles += 1
      }
      assert(seen0 && seen1, "both masters should make forward progress after backpressure release")
    }
  }

  "LiteBusROToRW converts read-only request into rw read transaction" in withSvsim {
    simulate(new LiteBusROToRW(32, 32, 8)) { dut =>
      dut.io.in.req.valid.poke(true.B)
      dut.io.in.req.bits.addr.poke("h00000100".U)
      dut.io.in.req.bits.user.poke("h7A".U)
      dut.io.in.resp.ready.poke(true.B)
      dut.io.in.resp.flush.poke(false.B)

      dut.io.out.req.ready.poke(true.B)
      dut.io.out.req.flush.expect(false.B)
      dut.io.out.req.valid.expect(true.B)
      dut.io.out.req.bits.addr.expect("h00000100".U)
      dut.io.out.req.bits.user.expect("h7A".U)
      dut.io.out.req.bits.wen.expect(false.B)
      dut.io.out.req.bits.wdata.expect(0.U)
      dut.io.out.req.bits.wstrb.expect(0.U)

      dut.io.out.resp.valid.poke(true.B)
      dut.io.out.resp.bits.data.poke("hABCD1234".U)
      dut.io.out.resp.bits.user.poke("h7A".U)
      dut.io.in.resp.valid.expect(true.B)
      dut.io.in.resp.bits.data.expect("hABCD1234".U)
      dut.io.in.resp.bits.user.expect("h7A".U)
      dut.clock.step()
    }
  }
}
