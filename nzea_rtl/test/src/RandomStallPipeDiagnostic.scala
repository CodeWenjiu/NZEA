package nzea_rtl

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class RandomStallPipeDiagnostic extends AnyFlatSpec with ChiselScalatestTester {

  "RandomStallPipe E=10" should "actually stall" in {
    test(new RandomStallPipe(UInt(8.W), 10.0)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0x42.U)
      dut.io.out.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.clock.step(20) // let LFSR warm up

      var fires = 0
      var inReady = 0
      for (_ <- 0 until 200) {
        if (dut.io.out.valid.peek().litToBoolean) fires += 1
        if (dut.io.in.ready.peek().litToBoolean) inReady += 1
        dut.clock.step()
      }
      // inReady low when pipe is full (stalling). With ~10% pass rate,
      // pipe should be full ~90% of the time → inReady high only ~10%
      assert(inReady < 60, s"in.ready high $inReady/200 cycles — pipe not stalling enough")
      assert(fires < 50, s"$fires fires in 200 cycles — LFSR not stalling")
    }
  }

}
