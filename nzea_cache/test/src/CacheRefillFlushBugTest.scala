package nzea_cache

import chisel3._
import chisel3.util.log2Ceil
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

/** Reproduces issue #4: `CacheRefillCtrl` drops in-flight refill responses after
  * a brief flush.
  *
  * Sequence:
  *   1. CPU issues read → miss → refill controller fires bottom.req
  *   2. While the response is still in flight, `io.top.resp.flush` pulses high
  *      for exactly one cycle, then drops. FSM returns to sIdle.
  *   3. The slave's response arrives later (slave has latency > 0).
  *
  * After step 3, in `CacheRefillCtrl`:
  *   - state == sIdle, so `io.bottom.resp.ready := io.flush` (default from L85).
  *   - flush has already dropped → `resp.ready = 0`.
  *   - The slave holds `resp.valid = 1` but no one drains it → the bus is
  *     stuck. Subsequent misses start new refills that send more req's, but
  *     the outstanding response never clears → eventually the crossbar
  *     backpressure saturates and the cache hangs.
  *
  * In the real trace.fst we observed 34446 refill req fires vs only 31899
  * storage writes — 2557 refills never completed. This is the smoking gun.
  *
  * The TB drives the bottom bus with a fixed latency (>> 1 cycle), pulses
  * `io.top.resp.flush` for 1 cycle immediately after the req is accepted, then
  * asserts that the cache must still be able to drain the in-flight response
  * — i.e. `io.bottom.resp.ready` must eventually go high so the bus does not
  * leak an outstanding slot.
  */
class CacheRefillFlushBugTest extends AnyFreeSpec with ChiselSim {

  private class RefillTop(
      addrWidth: Int = 32,
      dataWidth: Int = 32,
      lineBits: Int = 32,
      userWidth: Int = 8
  ) extends Module {
    val io = IO(new Bundle {
      val start = Input(Bool())
      val startAddr = Input(UInt(addrWidth.W))
      val startSetIdx = Input(UInt(4.W))
      val startTag = Input(UInt((addrWidth - 4 - log2Ceil(lineBits / 8)).W))
      val startWay = Input(UInt(2.W))
      val startUser = Input(UInt(userWidth.W))
      val startWordOff = Input(UInt(1.W))
      val flush = Input(Bool())

      // Bottom bus — driven externally (testbench acts as the slave).
      val bottomReqReady = Input(Bool())
      val bottomRespValid = Input(Bool())
      val bottomRespData = Input(UInt(lineBits.W))
      val bottomReqFire = Output(Bool())
      val bottomRespReady = Output(Bool())

      // Refill outputs (for observation).
      val wrValid = Output(Bool())
      val busy = Output(Bool())
    })

    private val refill = Module(
      new CacheRefillCtrl(
        addrWidth = addrWidth,
        dataWidth = dataWidth,
        lineBits = lineBits,
        userWidth = userWidth,
        indexBits = 4,
        tagBits = addrWidth - 4 - log2Ceil(lineBits / 8),
        wayBits = 2
      )
    )

    refill.io.start := io.start
    refill.io.startAddr := io.startAddr
    refill.io.startSetIdx := io.startSetIdx
    refill.io.startTag := io.startTag
    refill.io.startWay := io.startWay
    refill.io.startUser := io.startUser
    refill.io.startWordOff := io.startWordOff
    refill.io.flush := io.flush

    refill.io.bottom.req.ready := io.bottomReqReady
    refill.io.bottom.req.flush := false.B
    refill.io.bottom.resp.valid := io.bottomRespValid
    refill.io.bottom.resp.bits.data := io.bottomRespData
    refill.io.bottom.resp.bits.user := 0.U
    refill.io.bottom.resp.bits.id := 0.U

    io.bottomReqFire := refill.io.bottom.req.fire
    io.bottomRespReady := refill.io.bottom.resp.ready
    io.wrValid := refill.io.wrValid
    io.busy := refill.io.busy
  }

  "CacheRefillCtrl elaborates" in {
    ChiselStage.emitSystemVerilog(new CacheRefillCtrl(32, 32, 32, 8, 4, 26, 2))
  }

  "CacheRefillCtrl drains in-flight response after a 1-cycle flush (issue #4)" in {
    simulate(new RefillTop()) { dut =>
      // Quiesce everything.
      dut.io.start.poke(false.B)
      dut.io.startAddr.poke(0.U)
      dut.io.startSetIdx.poke(0.U)
      dut.io.startTag.poke(0.U)
      dut.io.startWay.poke(0.U)
      dut.io.startUser.poke(0.U)
      dut.io.startWordOff.poke(0.U)
      dut.io.flush.poke(false.B)
      dut.io.bottomReqReady.poke(true.B)
      dut.io.bottomRespValid.poke(false.B)
      dut.io.bottomRespData.poke(0.U)
      dut.clock.step(2)

      // Kick a refill. The slave immediately accepts the req.
      dut.io.start.poke(true.B)
      dut.io.startAddr.poke("h800049dc".U)
      dut.io.startSetIdx.poke("hc".U)
      dut.io.startTag.poke("h2000127".U)
      dut.io.startWay.poke(0.U)
      dut.clock.step()
      dut.io.start.poke(false.B)

      // Wait until refill controller fires bottom.req.
      var guard = 0
      while (!dut.io.bottomReqFire.peek().litToBoolean && guard < 20) {
        dut.clock.step(); guard += 1
      }
      assert(guard < 20, "refill controller never issued bottom.req")
      // req.fire happened this cycle. Move to the next cycle.
      dut.clock.step()

      // Pulse flush for exactly 1 cycle while the response is NOT yet on the bus.
      // FSM should drop back to sIdle once flush is registered.
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      // Busy should have dropped (state == sIdle).
      assert(!dut.io.busy.peek().litToBoolean,
        "FSM should be idle immediately after flush pulse")

      // Now the slave finally returns the response.
      dut.io.bottomRespValid.poke(true.B)
      dut.io.bottomRespData.poke("hdeadbeef".U)

      // Probe whether the controller drains the response.
      // Bug #4: `io.bottom.resp.ready := io.flush` in sIdle ⇒ stays 0 ⇒ never drains.
      var sawDrain = false
      for (i <- 0 until 20) {
        val valid = dut.io.bottomRespValid.peek().litToBoolean
        val ready = dut.io.bottomRespReady.peek().litToBoolean
        if (valid && ready) sawDrain = true
        dut.clock.step()
      }

      assert(sawDrain,
        "issue #4: in-flight response was never drained after a 1-cycle flush — " +
          "the controller only sets resp.ready in sWait or while flush is high, " +
          "so a straggler response stays stuck forever")
    }
  }
}