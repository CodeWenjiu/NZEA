package nzea_rtl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

class LiteBusXbarTest extends AnyFreeSpec with ChiselSim with LiteBusTestHelpers {

  private val ranges = Seq(
    LiteBusAddrRange(base = BigInt("00000000", 16), size = BigInt("00010000", 16)),
    LiteBusAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  )

  private case class Txn(
    addr: BigInt,
    user: BigInt,
    target: Int, // 0/1 for slave index, -1 for decode miss
    wen: Boolean = false,
    wdata: BigInt = 0,
    respData: BigInt = 0,
    respDelay: Int = 0
  )

  private def withSvsim(body: => Unit): Unit = {
    if (!hasVerilator) cancel("svsim backend unavailable: verilator not found in PATH")
    body
  }

  private def executeTxn(dut: LiteBusRWXbar, t: Txn): Unit = {
    driveReq(dut.io.in, addr = t.addr, user = t.user, wen = t.wen, wdata = t.wdata)
    if (t.target >= 0) {
      dut.io.out.zipWithIndex.foreach { case (o, i) => o.req.valid.expect(if (i == t.target) true.B else false.B) }
      dut.io.decodeMiss.expect(false.B)
    } else {
      dut.io.out.foreach(_.req.valid.expect(false.B))
      dut.io.decodeMiss.expect(true.B)
    }
    dut.clock.step()
    clearReq(dut.io.in)

    if (t.target >= 0) {
      dut.clock.step(t.respDelay)
      driveResp(dut.io.out(t.target), data = t.respData, user = t.user)
      dut.io.in.resp.bits.data.expect(t.respData.U)
      dut.io.in.resp.bits.user.expect(t.user.U)
      dut.clock.step()
      clearResp(dut.io.out(t.target))
    } else {
      dut.io.in.resp.bits.data.expect(0.U)
      dut.io.in.resp.bits.user.expect(t.user.U)
      dut.clock.step()
    }
  }

  "LiteBusRWXbar elaborates" in {
    ChiselStage.emitSystemVerilog(new LiteBusRWXbar(32, 32, 8, ranges))
  }

  "LiteBusROXbar elaborates" in {
    ChiselStage.emitSystemVerilog(new LiteBusROXbar(32, 32, 8, ranges))
  }

  "LiteBusRWXbar routes requests and returns decode miss response in svsim" in withSvsim {
    simulate(new LiteBusRWXbar(32, 32, 8, ranges)) { dut =>
      initMaster(dut.io.in)
      dut.io.out.foreach(o => initEndpoint(o, reqReady = true))
      dut.clock.step()
      Seq(
        Txn(addr = BigInt("00000020", 16), user = BigInt("12", 16), target = 0, respData = BigInt("A5A50020", 16)),
        Txn(addr = BigInt("10000010", 16), user = BigInt("2A", 16), target = 1, respData = BigInt("5A5A0010", 16)),
        Txn(addr = BigInt("20000000", 16), user = BigInt("3F", 16), target = -1)
      ).foreach(executeTxn(dut, _))
    }
  }

  "LiteBusRWXbar survives 100-cycle full backpressure then random release" in withSvsim {
    simulate(new LiteBusRWXbar(32, 32, 8, ranges)) { dut =>
      val rng = new Random(7)
      initMaster(dut.io.in)
      dut.io.out.foreach(o => initEndpoint(o, reqReady = false))

      driveReq(dut.io.in, addr = BigInt("00000080", 16), user = BigInt("55", 16), wdata = BigInt("12345678", 16))
      for (_ <- 0 until 100) {
        dut.io.in.req.ready.expect(false.B)
        dut.io.out(0).req.valid.expect(true.B)
        dut.clock.step()
      }

      var accepted = false
      var cycles = 0
      while (!accepted && cycles < 64) {
        dut.io.out(0).req.ready.poke(if (rng.nextBoolean()) true.B else false.B)
        dut.io.out(1).req.ready.poke(if (rng.nextBoolean()) true.B else false.B)
        if (dut.io.in.req.ready.peek().litToBoolean) accepted = true
        dut.clock.step()
        cycles += 1
      }
      assert(accepted, "request must be accepted after backpressure release")

      clearReq(dut.io.in)
      dut.io.out.foreach(_.req.ready.poke(true.B))
      dut.clock.step(5)
      driveResp(dut.io.out(0), data = BigInt("DEADBEEF", 16), user = BigInt("55", 16))
      dut.io.in.resp.bits.data.expect("hDEADBEEF".U)
      dut.io.in.resp.bits.user.expect("h55".U)
      dut.clock.step()
    }
  }

  "LiteBusRWXbar handles read/write interleaving across slaves" in withSvsim {
    simulate(new LiteBusRWXbar(32, 32, 8, ranges)) { dut =>
      initMaster(dut.io.in)
      dut.io.out.foreach(o => initEndpoint(o, reqReady = true))
      dut.clock.step()
      Seq(
        Txn(addr = BigInt("00000040", 16), user = BigInt("11", 16), target = 0, wen = true, wdata = BigInt("AAAA0001", 16), respData = 0),
        Txn(addr = BigInt("10000020", 16), user = BigInt("22", 16), target = 1, respData = BigInt("CAFEBABE", 16), respDelay = 2),
        Txn(addr = BigInt("1000002C", 16), user = BigInt("33", 16), target = 1, wen = true, wdata = BigInt("5555AAAA", 16), respData = 0),
        Txn(addr = BigInt("00000008", 16), user = BigInt("44", 16), target = 0, respData = BigInt("13579BDF", 16))
      ).foreach(executeTxn(dut, _))
    }
  }

  "LiteBusRWXbar accepts only one outstanding request at a time" in withSvsim {
    simulate(new LiteBusRWXbar(32, 32, 8, ranges)) { dut =>
      initMaster(dut.io.in)
      dut.io.out.foreach(o => initEndpoint(o, reqReady = true))
      dut.clock.step()

      driveReq(dut.io.in, addr = BigInt("00000010", 16), user = BigInt("61", 16), wdata = BigInt("11111111", 16))
      dut.io.in.req.ready.expect(true.B)
      dut.clock.step()

      driveReq(dut.io.in, addr = BigInt("10000010", 16), user = BigInt("62", 16), wdata = BigInt("22222222", 16))
      for (_ <- 0 until 5) {
        dut.io.in.req.ready.expect(false.B)
        dut.io.out(1).req.valid.expect(false.B)
        dut.clock.step()
      }
      driveResp(dut.io.out(0), data = BigInt("ABCD0001", 16), user = BigInt("61", 16))
      dut.clock.step()
      clearResp(dut.io.out(0))

      dut.io.in.req.ready.expect(true.B)
      dut.io.out(1).req.valid.expect(true.B)
      dut.clock.step()
      clearReq(dut.io.in)
      driveResp(dut.io.out(1), data = BigInt("ABCD0002", 16), user = BigInt("62", 16))
      dut.io.in.resp.bits.user.expect("h62".U)
      dut.clock.step()
    }
  }

  "LiteBusRWXbar rejects out-of-range address windows" in {
    val bad = Seq(LiteBusAddrRange(base = BigInt("100000000", 16), size = 0x1000))
    assertThrows[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(new LiteBusRWXbar(32, 32, 8, bad))
    }
  }
}
