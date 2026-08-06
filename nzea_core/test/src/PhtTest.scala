package nzea_core

import chisel3._
import chiseltest._
import nzea_core.frontend.bp.PHT
import org.scalatest.flatspec.AnyFlatSpec

/** PHT (PC-indexed 2-bit saturating counters) behavior. */
class PhtTest extends AnyFlatSpec with ChiselScalatestTester {

  private def doUpdate(dut: PHT, taken: Boolean): Unit = {
    dut.io.pc.poke(0.U)
    dut.io.update.poke(true.B)
    dut.io.update_pc.poke(0x100.U)
    dut.io.update_taken.poke(taken.B)
    dut.clock.step()
    dut.io.update.poke(false.B)
    // Let the two-cycle read-modify-write pipeline settle.
    dut.clock.step()
    dut.clock.step()
  }

  private def predict(dut: PHT): Boolean = {
    dut.io.pc.poke(0x100.U)
    dut.clock.step() // SyncReadMem read latency
    dut.io.pred_taken.peek().litToBoolean
  }

  "PHT" should "predict taken after repeated taken updates" in {
    var result = false
    test(new PHT(4)) { dut =>
      doUpdate(dut, taken = true)
      doUpdate(dut, taken = true)
      result = predict(dut)
    }
    assert(result, "2x taken updates should saturate the 2-bit counter")
  }

  it should "predict not-taken after repeated not-taken updates" in {
    var result = true
    test(new PHT(4)) { dut =>
      doUpdate(dut, taken = false)
      doUpdate(dut, taken = false)
      result = predict(dut)
    }
    assert(!result, "2x not-taken updates should saturate the counter to not-taken")
  }

  it should "require two flips to change a saturated prediction (hysteresis)" in {
    var result = false
    test(new PHT(4)) { dut =>
      // Saturate taken, then one not-taken: still predicts taken (weak taken).
      doUpdate(dut, taken = true)
      doUpdate(dut, taken = true)
      doUpdate(dut, taken = false)
      result = predict(dut)
    }
    assert(result, "one not-taken after saturation should only weaken, not flip")
  }

}
