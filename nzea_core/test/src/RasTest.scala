package nzea_core

import chisel3._
import chiseltest._
import nzea_core.frontend.bp.RAS
import org.scalatest.flatspec.AnyFlatSpec

/** RAS stack behavior: LIFO push/pop, empty-stack pop ignored, full-stack push dropped. */
class RasTest extends AnyFlatSpec with ChiselScalatestTester {

  "RAS(4)" should "push and pop LIFO with correct top" in {
    test(new RAS(4)) { dut =>
      dut.io.pop.poke(false.B)
      dut.io.push.poke(true.B)
      dut.io.push_data.poke(0x100.U)
      dut.clock.step()
      dut.io.push_data.poke(0x200.U)
      dut.clock.step()
      dut.io.push_data.poke(0x300.U)
      dut.clock.step()

      assert(dut.io.top_valid.peek().litToBoolean)
      dut.io.top.expect(0x300.U)

      dut.io.push.poke(false.B)
      dut.io.pop.poke(true.B)
      dut.clock.step()
      dut.io.top.expect(0x200.U)
      dut.clock.step()
      dut.io.top.expect(0x100.U)
      dut.clock.step()
      assert(!dut.io.top_valid.peek().litToBoolean)
    }
  }

  it should "ignore pop on an empty stack (no underflow)" in {
    test(new RAS(4)) { dut =>
      dut.io.push.poke(false.B)
      dut.io.pop.poke(true.B)
      for (_ <- 0 until 8) {
        dut.clock.step()
        assert(!dut.io.top_valid.peek().litToBoolean, "empty stack must stay empty")
      }
      // After the ignored pops, a push still lands at the bottom.
      dut.io.pop.poke(false.B)
      dut.io.push.poke(true.B)
      dut.io.push_data.poke(0xabc.U)
      dut.clock.step()
      dut.io.top.expect(0xabc.U)
    }
  }

  it should "drop push when full" in {
    test(new RAS(4)) { dut =>
      dut.io.pop.poke(false.B)
      dut.io.push.poke(true.B)
      for (i <- 0 until 4) {
        dut.io.push_data.poke((0x100 + i).U)
        dut.clock.step()
      }
      // Full: a further push must not overwrite the top.
      dut.io.push_data.poke(0x999.U)
      dut.clock.step()
      dut.io.top.expect(0x103.U)
      dut.clock.step()
      dut.io.top.expect(0x103.U)
    }
  }

}
