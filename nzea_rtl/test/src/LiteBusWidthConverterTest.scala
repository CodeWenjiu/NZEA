package nzea_rtl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

class LiteBusWidthConverterTest extends AnyFreeSpec with ChiselSim {

  private class ConvTop(wideDW: Int, narrowDW: Int) extends Module {
    val io = IO(new Bundle {
      val wide = Flipped(new LiteBusRW(32, wideDW, 8, 1))
      val nReqReady = Input(Bool())
      val nReqFlush = Input(Bool())
      val nReqValid = Output(Bool())
      val nReqAddr = Output(UInt(32.W))
      val nRespValid = Input(Bool())
      val nRespReady = Output(Bool())
      val nRespData = Input(UInt(narrowDW.W))
    })

    private val dut = Module(new LiteBusWidthConverter(wideDW, narrowDW, 32, 8, 1))
    dut.io.wide <> io.wide
    dut.io.narrow.req.ready := io.nReqReady
    dut.io.narrow.req.flush := io.nReqFlush
    io.nReqValid := dut.io.narrow.req.valid
    io.nReqAddr := dut.io.narrow.req.bits.addr
    io.nRespReady := dut.io.narrow.resp.ready
    dut.io.narrow.resp.valid := io.nRespValid
    dut.io.narrow.resp.bits.data := io.nRespData
    dut.io.narrow.resp.bits.user := DontCare
    dut.io.narrow.resp.bits.id := DontCare
  }

  "LiteBusWidthConverter elaborates (32→32)" in {
    ChiselStage.emitSystemVerilog(new LiteBusWidthConverter(32, 32, 32, 8, 1))
  }

  "LiteBusWidthConverter elaborates (128→32)" in {
    ChiselStage.emitSystemVerilog(new LiteBusWidthConverter(128, 32, 32, 8, 1))
  }

  "passthrough (32→32): wide req → narrow req, response loop" in {
    simulate(new ConvTop(32, 32)) { dut =>
      dut.io.wide.req.valid.poke(false.B)
      dut.io.wide.resp.ready.poke(true.B)
      dut.io.nReqReady.poke(true.B)
      dut.io.nReqFlush.poke(false.B)
      dut.io.nRespValid.poke(false.B)
      dut.clock.step(2)

      dut.io.wide.req.valid.poke(true.B)
      dut.io.wide.req.bits.addr.poke("h800049dc".U)
      dut.io.wide.req.bits.user.poke("hAA".U)
      dut.io.wide.req.bits.id.poke(1.U)
      dut.io.wide.req.bits.wen.poke(false.B)

      // The passthrough is combinational; req.valid appears on narrow side
      // in the same cycle.
      assert(dut.io.nReqValid.peek().litToBoolean, "narrow req not valid")
      dut.io.nReqAddr.expect("h800049dc".U)
      dut.clock.step()
      dut.io.wide.req.valid.poke(false.B)

      dut.io.nRespValid.poke(true.B)
      dut.io.nRespData.poke("hdeadbeef".U)

      assert(dut.io.wide.resp.valid.peek().litToBoolean, "wide resp not valid")
      dut.io.wide.resp.bits.data.expect("hdeadbeef".U)
      dut.clock.step()
      dut.io.nRespValid.poke(false.B)
    }
  }

  "multi-beat (128→32): 1 wide read → 4 narrow reqs → 4 resps → 1 wide resp" in {
    simulate(new ConvTop(128, 32)) { dut =>
      dut.io.wide.req.valid.poke(false.B)
      dut.io.wide.resp.ready.poke(true.B)
      dut.io.nReqReady.poke(true.B)
      dut.io.nReqFlush.poke(false.B)
      dut.io.nRespValid.poke(false.B)
      dut.clock.step(2)

      // Issue wide read
      dut.io.wide.req.valid.poke(true.B)
      dut.io.wide.req.bits.addr.poke("h80004A00".U)
      dut.io.wide.req.bits.user.poke("h55".U)
      dut.io.wide.req.bits.id.poke(1.U)
      dut.io.wide.req.bits.wen.poke(false.B)
      dut.clock.step()
      dut.io.wide.req.valid.poke(false.B)

      val expectedAddrs = Seq("h80004A00", "h80004A04", "h80004A08", "h80004A0C")
      val expectedData = Seq("h00000001", "h00000002", "h00000003", "h00000004")

      // Issue 4 narrow responses back
      for (i <- 0 until 4) {
        var guard = 0
        while (!dut.io.nReqValid.peek().litToBoolean && guard < 20) {
          dut.clock.step(); guard += 1
        }
        assert(guard < 20, s"narrow req beat $i never valid")
        dut.io.nReqAddr.expect(expectedAddrs(i).U)
        dut.clock.step()
      }

      // Provide 4 narrow responses
      for (i <- 0 until 4) {
        var guard = 0
        while (!dut.io.nRespReady.peek().litToBoolean && guard < 20) {
          dut.clock.step(); guard += 1
        }
        assert(guard < 20, s"narrow resp ready never asserted after beat $i")
        dut.io.nRespValid.poke(true.B)
        dut.io.nRespData.poke(expectedData(i).U)
        dut.clock.step()
        dut.io.nRespValid.poke(false.B)
        if (i < 3) dut.clock.step()
      }

      // Wide response should appear after sAssemble→sResp
      var guard = 0
      while (!dut.io.wide.resp.valid.peek().litToBoolean && guard < 10) {
        dut.clock.step(); guard += 1
      }
      assert(guard < 10, "wide resp never valid after 4 narrow responses")

      val assembled: BigInt =
        (BigInt("00000004", 16) << 96) |
          (BigInt("00000003", 16) << 64) |
          (BigInt("00000002", 16) << 32) |
          BigInt("00000001", 16)
      dut.io.wide.resp.bits.data.expect(assembled.U)
    }
  }
}