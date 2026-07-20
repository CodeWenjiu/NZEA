package nzea_cache

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

/** Tests for [[SetAssoc]]: replacement policy, cross-set isolation, and
  * backpressure during refill.
  *
  * The fake memory bus accepts every req instantly and replies after a
  * per-test configurable number of cycles (`responseDelay`). A `refillCount`
  * output lets each test observe how many cacheline refills actually fire.
  */
class SetAssocWayAllocTest extends AnyFreeSpec with ChiselSim {

  // ── DUT wrapper ──────────────────────────────────────────────────

  private class CacheTop(
      nSets: Int,
      nWays: Int,
      responseDelay: Int = 1 // cycles between top.req.fire and top.resp.valid
  ) extends Module {
    val io = IO(new Bundle {
      val top = Flipped(new nzea_rtl.LiteBusRW(32, 32, 8, 1))
      val refillCount = Output(UInt(32.W))
    })

    private val cache = Module(
      new SetAssoc(
        nSets = nSets,
        nWays = nWays,
        lineBits = 32,
        addrWidth = 32,
        dataWidth = 32,
        userWidth = 8
      )
    )
    cache.io.top <> io.top

    // Fake memory bus — shift register for configurable response delay.
    private val shift = RegInit(VecInit(Seq.fill(responseDelay)(false.B)))
    cache.io.bottom.req.ready := true.B
    cache.io.bottom.req.flush := false.B
    cache.io.bottom.resp.valid := shift.last
    cache.io.bottom.resp.bits.data := 0.U
    cache.io.bottom.resp.bits.user := 0.U
    cache.io.bottom.resp.bits.id := 0.U

    private val fire = cache.io.bottom.req.fire
    shift(0) := fire
    for (i <- 1 until responseDelay) { shift(i) := shift(i - 1) }

    private val cnt = RegInit(0.U(32.W))
    when(fire) { cnt := cnt + 1.U }
    io.refillCount := cnt
  }

  // ── Test helpers ─────────────────────────────────────────────────

  /** Thin wrapper over a CacheTop DUT that provides issue / awaitResp /
    * init helpers so every test case can share them without copy-paste.
    */
  private class Ctx(dut: CacheTop) {
    def init(): Unit = {
      dut.io.top.req.valid.poke(false.B)
      dut.io.top.resp.ready.poke(true.B)
      dut.io.top.resp.flush.poke(false.B)
      dut.clock.step(2)
    }

    def issue(addr: BigInt): Unit = {
      dut.io.top.req.valid.poke(true.B)
      dut.io.top.req.bits.addr.poke(addr.U)
      dut.io.top.req.bits.user.poke(0.U)
      dut.io.top.req.bits.id.poke(0.U)
      dut.io.top.req.bits.wen.poke(false.B)
      dut.io.top.req.bits.wdata.poke(0.U)
      dut.io.top.req.bits.wstrb.poke(0xf.U)
      var guard = 0
      while (!dut.io.top.req.ready.peek().litToBoolean && guard < 80) {
        dut.clock.step(); guard += 1
      }
      assert(guard < 80, s"req for addr=0x${addr.toString(16)} never accepted")
      dut.clock.step() // capture into T0
      dut.io.top.req.valid.poke(false.B)
    }

    def awaitResp(): Unit = {
      var guard = 0
      while (!dut.io.top.resp.valid.peek().litToBoolean && guard < 80) {
        dut.clock.step(); guard += 1
      }
      assert(guard < 80, "resp never returned")
      dut.clock.step() // consume
    }

    def refillCount: Int = dut.io.refillCount.peek().litValue.toInt

    /** Issue and wait in a single call. Returns whether a new refill fired
      * (true = miss, false = hit).
      */
    def read(addr: BigInt): Boolean = {
      val before = refillCount
      issue(addr); awaitResp()
      refillCount != before
    }
  }

  // ── Helpers to build addresses ──────────────────────────────────

  // offsetBits = log2(32/8) = 2, indexBits = log2(nSets)
  private def mkAddr(setIdx: Int, tag: BigInt): BigInt =
    (tag << 4) | (setIdx << 2)

  // ── Tests ─────────────────────────────────────────────────────────

  "SetAssoc elaborates" in {
    ChiselStage.emitSystemVerilog(
      new SetAssoc(nSets = 4, nWays = 4, lineBits = 32, addrWidth = 32, dataWidth = 32, userWidth = 8)
    )
  }

  // ── Replacement correctness ─────────────────────────────────

  "way allocator distributes writes across all free ways before reuse" in {
    simulate(new CacheTop(nSets = 4, nWays = 4)) { dut =>
      val c = new Ctx(dut); c.init()

      val tags = Seq(0x10, 0x20, 0x30, 0x40).map(BigInt(_))
      val addrs = tags.map(mkAddr(2, _))

      // Phase 1: install 4 distinct tags into one set. Each must miss+refill.
      for (a <- addrs) { c.read(a) }
      assert(c.refillCount == 4,
        s"phase 1 should trigger exactly 4 refills, got ${c.refillCount}")

      // Phase 2: re-read the 4 tags. All must hit — 0 refills.
      val before = c.refillCount
      for (a <- addrs) { c.read(a) }
      val phase2Refills = c.refillCount - before
      assert(phase2Refills == 0,
        s"phase 2 (re-read) should not refill, got $phase2Refills refills")
    }
  }

  "way allocator uses LRU replacement once the set is full (issue #5)" in {
    simulate(new CacheTop(nSets = 4, nWays = 4)) { dut =>
      val c = new Ctx(dut); c.init()

      // Install 6 distinct tags into a 4-way set. After 6 installs with PLRU
      // the 2 oldest tags should be evicted.
      val tags = Seq(0x10, 0x20, 0x30, 0x40, 0x50, 0x60).map(BigInt(_))
      val addrs = tags.map(mkAddr(2, _))

      for (a <- addrs) { c.read(a) }
      assert(c.refillCount == 6, s"all 6 installs should refill, got ${c.refillCount}")

      // Re-read from newest to oldest to keep PLRU state deterministic.
      val reverseAddrs = addrs.reverse
      val before = c.refillCount
      val hitMiss = reverseAddrs.map(a => c.read(a))
      val phase2Refills = c.refillCount - before
      val hits = hitMiss.count(_ == false)
      val misses = hitMiss.count(_ == true)

      info(s"6-tag re-read (reverse): hits=$hits misses=$misses refills=$phase2Refills")
      // With PLRU we expect at most 3 misses (not perfect LRU), but worst-case
      // the bug (#5 collapse) would cause most tags to miss.
      assert(phase2Refills <= 3,
        s"bug #5: 6-tag re-read (reverse) should miss at most 3 (PLRU slack) " +
          s"but got $phase2Refills refills")
    }
  }

  // ── Cross-set isolation ────────────────────────────────────────

  "filling set A does not evict lines in set B" in {
    simulate(new CacheTop(nSets = 4, nWays = 4)) { dut =>
      val c = new Ctx(dut); c.init()

      // Fill set 0 with 4 tags.
      val set0Tags = Seq(0x10, 0x20, 0x30, 0x40).map(BigInt(_))
      for (t <- set0Tags) { c.read(mkAddr(0, t)) }
      assert(c.refillCount == 4)

      // Fill set 1 with a completely different workload — should NOT
      // evict anything in set 0.
      val set1Tags = Seq(0x50, 0x60, 0x70, 0x80).map(BigInt(_))
      for (t <- set1Tags) { c.read(mkAddr(1, t)) }
      assert(c.refillCount == 8) // 4 for set 0 + 4 for set 1

      // Re-read set 0 tags — all 4 must still be resident.
      val before = c.refillCount
      for (t <- set0Tags) { c.read(mkAddr(0, t)) }
      assert(c.refillCount == before,
        s"set-0 lines evicted by set-1 traffic: ${c.refillCount - before} extra refills")
    }
  }

  // ── Backpressure during refill ─────────────────────────────────

  "top.req.ready stays de-asserted while a refill is in progress" in {
    simulate(new CacheTop(nSets = 4, nWays = 4, responseDelay = 8)) { dut =>
      val c = new Ctx(dut); c.init()

      // Issue a read that will miss and trigger a refill.
      val addr = mkAddr(2, 0x10)
      dut.io.top.req.valid.poke(true.B)
      dut.io.top.req.bits.addr.poke(addr.U)
      dut.io.top.req.bits.user.poke(0.U)
      dut.io.top.req.bits.id.poke(0.U)
      dut.io.top.req.bits.wen.poke(false.B)
      dut.io.top.req.bits.wdata.poke(0.U)
      dut.io.top.req.bits.wstrb.poke(0xf.U)

      // Wait for req acceptance.
      while (!dut.io.top.req.ready.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step() // capture request
      dut.io.top.req.valid.poke(false.B)

      // Immediately issue a second request. It must be blocked because
      // the refill controller is busy fetching the first miss.
      dut.io.top.req.valid.poke(true.B)
      dut.io.top.req.bits.addr.poke(addr.U) // same or different addr — shouldn't matter

      // req.ready should be false for at least one cycle while busy.
      var sawBlocked = false
      var guard = 0
      while (!dut.io.top.req.ready.peek().litToBoolean && guard < 40) {
        sawBlocked = true
        dut.clock.step(); guard += 1
      }
      dut.io.top.req.valid.poke(false.B)

      assert(sawBlocked,
        "top.req.ready must be de-asserted during refill busy period")
      assert(guard >= 2,
        s"busy period too short ($guard cycles) — responseDelay=8 should keep refill busy for several cycles")
    }
  }

}