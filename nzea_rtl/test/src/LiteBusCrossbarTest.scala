package nzea_rtl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

class LiteBusCrossbarTest extends AnyFreeSpec with ChiselSim with LiteBusTestHelpers {
  private val ranges = Seq(
    LiteBusAddrRange(base = BigInt("00000000", 16), size = BigInt("00010000", 16)),
    LiteBusAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  )

  private def withSvsim(body: => Unit): Unit = {
    if (!hasVerilator) cancel("svsim backend unavailable: verilator not found in PATH")
    body
  }

  private class Xbar2x2Top extends Module {
    val io = IO(new Bundle {
      val in = Vec(2, Flipped(new LiteBusRW(32, 32, 8)))
      val out = Vec(2, new LiteBusRW(32, 32, 8))
      val decodeMiss = Output(Vec(2, Bool()))
    })

    class MasterTap extends Module {
      val io = IO(new Bundle {
        val ext = Flipped(new LiteBusRW(32, 32, 8))
        val bus = new LiteBusRW(32, 32, 8)
      })
      io.bus <> io.ext
    }

    class SlaveTap extends Module {
      val io = IO(new Bundle {
        val ext = new LiteBusRW(32, 32, 8)
        val bus = Flipped(new LiteBusRW(32, 32, 8))
      })
      io.bus <> io.ext
    }

    val m0 = Module(new MasterTap)
    val m1 = Module(new MasterTap)
    val s0 = Module(new SlaveTap)
    val s1 = Module(new SlaveTap)
    m0.io.ext <> io.in(0)
    m1.io.ext <> io.in(1)
    s0.io.ext <> io.out(0)
    s1.io.ext <> io.out(1)

    val xbar = LiteBusRWCrossbar(ranges) { x =>
      x <> m0.io.bus
      x <> m1.io.bus
      x <> s0.io.bus
      x <> s1.io.bus
    }
    io.decodeMiss := xbar.io.decodeMiss
  }

  "LiteBusRWCrossbar elaborates via auto-inferred builder" in {
    ChiselStage.emitSystemVerilog(new Xbar2x2Top)
  }

  "LiteBusRWCrossbar supports incremental single-link syntax" in {
    class IncrementalTop extends Module {
      class MasterStub extends Module {
        val io = IO(new Bundle {
          val bus = new LiteBusRW(32, 32, 8)
        })
        io.bus.req.valid := false.B
        io.bus.req.bits := 0.U.asTypeOf(io.bus.req.bits)
        io.bus.resp.ready := true.B
        io.bus.resp.flush := false.B
      }

      class SlaveStub extends Module {
        val io = IO(new Bundle {
          val bus = Flipped(new LiteBusRW(32, 32, 8))
        })
        io.bus.req.ready := true.B
        io.bus.req.flush := false.B
        io.bus.resp.valid := false.B
        io.bus.resp.bits := 0.U.asTypeOf(io.bus.resp.bits)
      }

      val m0 = Module(new MasterStub)
      val m1 = Module(new MasterStub)
      val s0 = Module(new SlaveStub)
      val s1 = Module(new SlaveStub)
      LiteBusRWCrossbar(ranges) { x =>
        x <> m0.io.bus
        x <> s0.io.bus
        x <> m1.io.bus
        x <> s1.io.bus
      }
    }
    ChiselStage.emitSystemVerilog(new IncrementalTop)
  }

  "LiteBusRWCrossbar builder infers numMasters from <> links" in {
    class BuilderTop extends Module {
      class MasterStub extends Module {
        val io = IO(new Bundle {
          val bus = new LiteBusRW(32, 32, 8)
        })
        io.bus.req.valid := false.B
        io.bus.req.bits := 0.U.asTypeOf(io.bus.req.bits)
        io.bus.resp.ready := true.B
        io.bus.resp.flush := false.B
      }

      class SlaveStub extends Module {
        val io = IO(new Bundle {
          val bus = Flipped(new LiteBusRW(32, 32, 8))
        })
        io.bus.req.ready := true.B
        io.bus.req.flush := false.B
        io.bus.resp.valid := false.B
        io.bus.resp.bits := 0.U.asTypeOf(io.bus.resp.bits)
      }

      val m0 = Module(new MasterStub)
      val m1 = Module(new MasterStub)
      val s0 = Module(new SlaveStub)
      val s1 = Module(new SlaveStub)
      LiteBusRWCrossbar(ranges) { x =>
        x <> m0.io.bus
        x <> m1.io.bus
        x <> s0.io.bus
        x <> s1.io.bus
      }
    }
    ChiselStage.emitSystemVerilog(new BuilderTop)
  }

  "LiteBusRWCrossbar issues in parallel to different slaves" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true)
      initEndpoint(dut.io.out(1), reqReady = true)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000010", 16), user = BigInt("11", 16))
      driveReq(dut.io.in(1), addr = BigInt("10000010", 16), user = BigInt("22", 16))
      dut.io.out(0).req.valid.expect(true.B)
      dut.io.out(1).req.valid.expect(true.B)
      dut.io.in(0).req.ready.expect(true.B)
      dut.io.in(1).req.ready.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in(0))
      clearReq(dut.io.in(1))

      driveResp(dut.io.out(0), data = BigInt("AAAABBBB", 16), user = BigInt("11", 16))
      driveResp(dut.io.out(1), data = BigInt("CCCCDDDD", 16), user = BigInt("22", 16))
      dut.io.in(0).resp.valid.expect(true.B)
      dut.io.in(1).resp.valid.expect(true.B)
      dut.io.in(0).resp.bits.data.expect("hAAAABBBB".U)
      dut.io.in(1).resp.bits.data.expect("hCCCCDDDD".U)
      dut.io.in(0).resp.bits.user.expect("h11".U)
      dut.io.in(1).resp.bits.user.expect("h22".U)
      dut.clock.step()
    }
  }

  "LiteBusRWCrossbar round-robin on same slave avoids starvation" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = true, respValid = true)
      initEndpoint(dut.io.out(1), reqReady = true, respValid = false)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000020", 16), user = BigInt("10", 16))
      var firstGrantM1 = -1
      val m1Start = 6
      for (cycle <- 0 until 40) {
        if (cycle >= m1Start) driveReq(dut.io.in(1), addr = BigInt("00000024", 16), user = BigInt("20", 16))
        else clearReq(dut.io.in(1))
        if (cycle >= m1Start && firstGrantM1 < 0 && dut.io.in(1).req.ready.peek().litToBoolean) {
          firstGrantM1 = cycle
        }
        dut.clock.step()
      }
      assert(firstGrantM1 >= 0, "master1 should eventually be granted on shared slave")
      assert(firstGrantM1 - m1Start <= 12, s"master1 grant delay too high: ${firstGrantM1 - m1Start}")
    }
  }

  "LiteBusRWCrossbar survives 100-cycle full backpressure and recovers both masters" in withSvsim {
    simulate(new Xbar2x2Top) { dut =>
      initMaster(dut.io.in(0))
      initMaster(dut.io.in(1))
      initEndpoint(dut.io.out(0), reqReady = false)
      initEndpoint(dut.io.out(1), reqReady = false)
      dut.clock.step()

      driveReq(dut.io.in(0), addr = BigInt("00000040", 16), user = BigInt("31", 16))
      driveReq(dut.io.in(1), addr = BigInt("10000040", 16), user = BigInt("32", 16))
      for (_ <- 0 until 100) {
        dut.io.in(0).req.ready.expect(false.B)
        dut.io.in(1).req.ready.expect(false.B)
        dut.clock.step()
      }

      dut.io.out(0).req.ready.poke(true.B)
      dut.io.out(1).req.ready.poke(true.B)
      dut.io.out(0).resp.valid.poke(true.B)
      dut.io.out(1).resp.valid.poke(true.B)
      dut.io.out(0).resp.bits.data.poke("hABCD0001".U)
      dut.io.out(1).resp.bits.data.poke("hABCD0002".U)
      dut.io.out(0).resp.bits.user.poke("h31".U)
      dut.io.out(1).resp.bits.user.poke("h32".U)

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
}
