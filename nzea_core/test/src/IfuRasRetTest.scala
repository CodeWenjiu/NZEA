package nzea_core

import chisel3._
import chiseltest._
import nzea_config.core.BpuConfig
import nzea_config.core.CoreConfig
import nzea_core.frontend.IFU
import org.scalatest.flatspec.AnyFlatSpec

/** IFU RAS redirect: a `ret` recognized at the IFU output must be delivered to the downstream even when it is stalled
  * (`out.ready=0`) — the RAS redirect must not drop the ret by bumping the epoch before it fires. Regression for the
  * core-target difftest mismatch (ret at 0x800109b0 dropped, next commit skipped to the return-target lw).
  *
  * Scenario: fetch runs freely to RET_PC, then the downstream stalls exactly when the `ret` is at the IFU output. The
  * ret must stay valid (not dropped by the epoch bump) and, once un-stalled, must fire and redirect fetch to the RAS
  * top.
  */
class IfuRasRetTest extends AnyFlatSpec with ChiselScalatestTester {

  private implicit val config: CoreConfig = CoreConfig(
    isa = "riscv32i",
    defaultPc = 0x8000_0000L,
    robDepth = 16,
    issueQueueDepth = 4,
    prfDepth = 64,
    vlen = 128,
    vrfDepth = 64,
    viqDepth = 8,
    bpu = BpuConfig.typical, // rasDepth = Some(8)
    sim = false
  )

  private val RET = 0x00008067
  private val RETURN_TARGET = 0x80010eecL
  private val RET_PC = 0x800109b0L

  "IFU RAS ret" should "deliver a ret to a stalled downstream and redirect to RAS top" in {
    test(new IFU) { dut =>
      dut.io.bp_update.valid.poke(false.B)
      dut.io.ras_update.valid.poke(false.B)
      dut.io.redirect_pc.poke(0x80000000L.U)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.clock.step(2)

      // ---- Push RETURN_TARGET onto the RAS via a call bp_update (pushes pc+4) ----
      dut.io.bp_update.valid.poke(true.B)
      dut.io.bp_update.bits.pc.poke((RETURN_TARGET - 4).U)
      dut.io.bp_update.bits.taken.poke(true.B)
      dut.io.bp_update.bits.target.poke(RETURN_TARGET.U)
      dut.io.bp_update.bits.is_call.foreach(_.poke(true.B))
      dut.clock.step()
      dut.io.bp_update.valid.poke(false.B)
      dut.clock.step()

      var pendingValid = false
      var pendingData = BigInt(0)
      var pendingUser = BigInt(0)

      var stalled = false
      var heldRetCycles = 0
      var firedRet = false
      var fetchedTarget = false

      for (_ <- 0 until 40000) {
        if (pendingValid) {
          dut.io.bus.resp.valid.poke(true.B)
          dut.io.bus.resp.bits.data.poke(pendingData)
          dut.io.bus.resp.bits.user.poke(pendingUser)
          dut.io.bus.resp.bits.id.poke(0.U)
        } else {
          dut.io.bus.resp.valid.poke(false.B)
        }

        val outValid = dut.io.out.valid.peek().litToBoolean
        val outInst = if (outValid) dut.io.out.bits.inst.peek().litValue else BigInt(-1)
        val outReady = dut.io.out.ready.peek().litToBoolean

        if (outValid && outInst == RET) {
          if (!stalled) {
            dut.io.out.ready.poke(false.B) // stall downstream at the ret
            stalled = true
          }
          if (stalled) {
            heldRetCycles += 1 // the ret stays presented while stalled
          }
        }

        if (dut.io.bus.req.bits.addr.peek().litValue == RETURN_TARGET) {
          fetchedTarget = true
        }

        val reqFire = dut.io.bus.req.valid.peek().litToBoolean && dut.io.bus.req.ready.peek().litToBoolean
        if (reqFire && !pendingValid) {
          val user = dut.io.bus.req.bits.user.peek().litValue
          val addr = dut.io.bus.req.bits.addr.peek().litValue
          pendingData = if (addr == RET_PC) BigInt(RET) else BigInt(0x00000013L)
          pendingUser = user
          pendingValid = true
        }

        dut.clock.step()

        // After the ret has been held a few cycles, un-stall so it can fire.
        if (stalled && heldRetCycles > 4 && !firedRet) {
          dut.io.out.ready.poke(true.B)
          if (dut.io.out.valid.peek().litToBoolean && dut.io.out.bits.inst.peek().litValue == RET) {
            firedRet = true
          }
        }

        if (pendingValid) {
          val fired = dut.io.bus.resp.valid.peek().litToBoolean && dut.io.bus.resp.ready.peek().litToBoolean
          if (fired) pendingValid = false
        }
        dut.io.bus.resp.valid.poke(false.B)
      }

      assert(heldRetCycles > 1, "ret was not held at the IFU output while downstream stalled")
      assert(fetchedTarget, "fetch never redirected to RAS target 0x" + java.lang.Long.toHexString(RETURN_TARGET))
    }
  }

}
