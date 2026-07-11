package nzea_rtl

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class RandomStallPipeTest extends AnyFlatSpec with ChiselScalatestTester {

  /** Run the pipe for `cycles` with input always valid and output always ready. Returns the observed mean interval (=
    * cycles / fires).
    */
  private def measureMean(expectedCycles: Double, cycles: Int): Double = {
    var fires = 0L
    test(new RandomStallPipe(UInt(8.W), expectedCycles)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.flush.poke(false.B)
      dut.clock.setTimeout(cycles + 100)

      for (_ <- 0 until cycles) {
        val f = dut.io.out.valid.peek().litToBoolean && dut.io.out.ready.peek().litToBoolean
        if (f) fires += 1
        dut.clock.step()
      }
    }
    cycles.toDouble / fires.max(1)
  }

  "RandomStallPipe" should "have mean interval ≈ 2.0 for expectedCycles=1" in {
    val mean = measureMean(1.0, 5000)
    assert(mean >= 1.9 && mean <= 2.1, s"mean=$mean, expected ~2.0")
  }

  it should "have mean interval ≈ 2.0 for expectedCycles=1.5" in {
    val mean = measureMean(1.5, 20000)
    assert(mean >= 1.8 && mean <= 2.2, s"mean=$mean, expected ~2.0")
  }

  it should "have mean interval ≈ 4.0 for expectedCycles=4" in {
    val mean = measureMean(4.0, 20000)
    assert(mean >= 3.4 && mean <= 4.6, s"mean=$mean, expected ~4.0")
  }

  it should "have mean interval ≈ 10.0 for expectedCycles=10" in {
    val mean = measureMean(10.0, 500000)
    assert(mean >= 8.5 && mean <= 11.5, s"mean=$mean, expected ~10.0")
  }

  "LFSR E=1000" should "produce fires throughout full period of 65535" in {
    test(new RandomStallPipe(UInt(8.W), 1000.0)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.flush.poke(false.B)
      dut.clock.setTimeout(66000)

      var fireCount = 0L
      var cyclesSinceLastFire = 0L
      var maxGap = 0L

      for (_ <- 0L until 65536L) {
        cyclesSinceLastFire += 1
        if (dut.io.out.valid.peek().litToBoolean) {
          fireCount += 1
          if (cyclesSinceLastFire > maxGap) maxGap = cyclesSinceLastFire
          cyclesSinceLastFire = 0L
        }
        dut.clock.step()
      }

      assert(fireCount > 0, "LFSR should produce fires in 65536 cycles")
      // E=1000 → ~65 fires expected; max gap should be < 65535 (not stuck)
      assert(maxGap < 65535, s"LFSR stuck: max gap=$maxGap cycles")
    }
  }

  it should "clear buffer on flush" in {
    test(new RandomStallPipe(UInt(8.W), 1000.0)) { dut =>
      // Fill the pipe
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0x42.U)
      dut.io.out.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.clock.step()
      dut.clock.step()

      // Now flush
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      // Output should be invalid after flush
      dut.io.out.valid.expect(false.B)
    }
  }

}
