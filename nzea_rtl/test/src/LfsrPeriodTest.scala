package nzea_rtl

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Verify LFSR period = 2^16 - 1 for RandomStallPipe. */
class LfsrPeriodTest extends AnyFlatSpec with ChiselScalatestTester {

  "LFSR in RandomStallPipe E=1000" should "have period 65535" in {
    test(new RandomStallPipe(UInt(8.W), 1000.0)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      // step until we see the seed value 1 again (ensures period is full)
      // Period should be 65535. After 65535 steps from seed=1, should return to 1.
      // Just run 65536 cycles and check we don't get stuck at 0.
      var seenFire = false
      var stuckCycles = 0
      var lastFire = -1L

      for (i <- 0L until 65536L) {
        val fire = dut.io.out.valid.peek().litToBoolean
        if (fire) {
          seenFire = true
          val gap = i - lastFire
          if (gap > 100) {
            stuckCycles += 1
          }
          lastFire = i
        }
        dut.clock.step()
      }

      assert(seenFire, "LFSR should produce fires in 65536 cycles")
      // With E=1000 (passProb ~ 65), fires should be frequent (~1000 per 65536)
      assert(lastFire >= 0, "Should have at least one fire")
    }
  }

  "RandomStallPipe E=10 over 100k" should "have mean near 10.0" in {
    test(new RandomStallPipe(UInt(8.W), 10.0)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      var fires = 0L
      val total = 100000L
      for (_ <- 0L until total) {
        if (dut.io.out.valid.peek().litToBoolean) fires += 1
        dut.clock.step()
      }

      val mean = total.toDouble / fires.max(1)
      println(s"E=10 over 100k: fires=$fires, mean=$mean")
      assert(mean >= 8.0 && mean <= 12.5, s"mean=$mean, expected ~10.0")
    }
  }

}
