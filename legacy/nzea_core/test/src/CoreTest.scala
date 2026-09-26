package nzea_core

import chisel3._
import chiseltest._
import nzea_config.core.CoreConfig
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Smoke test for the baseline in-order core ([[Core]]).
  *
  * Drives a small hand-assembled program through the ibus/dbus bus model and checks the full commit stream: forward
  * (addi from addi), consecutive forward, store, load, load-to-use forward, and a branch that skips one instruction.
  * The branch is always-not-taken at fetch; resolution flushes the wrongly fetched instruction.
  */
class CoreTest extends AnyFlatSpec with ChiselScalatestTester {

  private implicit val config: CoreConfig = CoreConfig(
    isa = "riscv32i",
    defaultPc = 0x8000_0000L,
    sim = false
  )

  // (addr -> inst): addi x1,x0,100; addi x2,x1,5; addi x3,x2,-3; sw x3,8(x0);
  // lw x4,8(x0); addi x5,x4,1; beq x5,x5,8; [skipped] addi x6,x0,99;
  // addi x7,x0,7; ecall.
  // Baseline ISA is riscv32i (M extension not exercised on the in-order engine).
  private val Code = Seq(
    0x8000_0000L -> 0x06400093L,
    0x8000_0004L -> 0x00508113L,
    0x8000_0008L -> 0xffd10193L,
    0x8000_000cL -> 0x00302423L,
    0x8000_0010L -> 0x00802203L,
    0x8000_0014L -> 0x00120293L,
    0x8000_0018L -> 0x00528463L,
    0x8000_001cL -> 0x06300313L,
    0x8000_0020L -> 0x00700393L,
    0x8000_0024L -> 0x00000073L
  )

  // Expected commit stream: (next_pc, rd_index, rd_value) — rd_value checked only when rd != 0.
  private val Expected = Seq(
    (0x8000_0004L, 1, 100L),
    (0x8000_0008L, 2, 105L),
    (0x8000_000cL, 3, 102L),
    (0x8000_0010L, 0, 0L), // sw
    (0x8000_0014L, 4, 102L),
    (0x8000_0018L, 5, 103L),
    (0x8000_0020L, 0, 0L), // beq (next_pc = taken target)
    (0x8000_0024L, 7, 7L),
    (0x8000_0028L, 0, 0L) // ecall
  )

  private case class Pending(addr: Long, user: BigInt, countdown: Int)

  "Core" should "commit the expected stream with forwarding, LSU and branch flush" in {
    test(new Core(Seq.empty)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      val mem = mutable.Map[Long, Long]()
      Code.foreach { case (a, d) => mem(a) = d }

      // Fabric refuses requests while reset is asserted (reset is a fabric concern, not transport — the master
      // side stays pure Decoupled, see Ifu). The model mirrors the crossbar/DPI-bridge behavior here.
      def pokeBusReady(): Unit = {
        val ready = !dut.reset.asBool.peek().litToBoolean
        dut.io.ibus.req.ready.poke(ready)
        dut.io.dbus.req.ready.poke(ready)
      }
      pokeBusReady()

      var ibPending: List[Pending] = Nil
      var dbPending: Option[Pending] = None
      val commits = mutable.ArrayBuffer[(Long, Int, Long)]()

      var cycle = 0
      while (commits.size < Expected.size && cycle < 200000) {
        cycle += 1
        pokeBusReady()
        // ---- bus responses (fixed 2-cycle latency after req fire) ----
        ibPending.headOption match {
          case Some(p) if p.countdown == 0 =>
            dut.io.ibus.resp.valid.poke(true.B)
            dut.io.ibus.resp.bits.data.poke((mem.getOrElse(p.addr, 0L) & 0xffffffffL).U) // 32-bit word
            dut.io.ibus.resp.bits.user.poke(p.user)
            dut.io.ibus.resp.bits.id.poke(0.U)
          case _ =>
            dut.io.ibus.resp.valid.poke(false.B)
        }
        dbPending match {
          case Some(p) if p.countdown == 0 =>
            dut.io.dbus.resp.valid.poke(true.B)
            dut.io.dbus.resp.bits.data.poke((mem.getOrElse(p.addr, 0L) & 0xffffffffL).U)
            dut.io.dbus.resp.bits.user.poke(p.user)
            dut.io.dbus.resp.bits.id.poke(0.U)
          case _ =>
            dut.io.dbus.resp.valid.poke(false.B)
        }

        // ---- collect commits ----
        if (dut.io.commit_msg.valid.peek().litToBoolean) {
          val nextPc = dut.io.commit_msg.bits.next_pc.peek().litValue.toLong
          val rd = dut.io.commit_msg.bits.rd_index.peek().litValue.toInt
          val rdv = dut.io.commit_msg.bits.rd_value.peek().litValue.toLong
          commits += ((nextPc, rd, rdv))
        }

        // ---- ibus req (FIFO: IFU issues a new req every cycle while un-stalled) ----
        val ibFire = dut.io.ibus.req.valid.peek().litToBoolean && dut.io.ibus.req.ready.peek().litToBoolean
        if (ibFire) {
          ibPending = ibPending :+ Pending(
            dut.io.ibus.req.bits.addr.peek().litValue.toLong,
            dut.io.ibus.req.bits.user.peek().litValue,
            2
          )
        }
        // ---- dbus req ----
        val dbFire = dut.io.dbus.req.valid.peek().litToBoolean && dut.io.dbus.req.ready.peek().litToBoolean
        if (dbFire) {
          val addr = dut.io.dbus.req.bits.addr.peek().litValue.toLong
          val wdata = dut.io.dbus.req.bits.wdata.peek().litValue.toLong
          val wen = dut.io.dbus.req.bits.wen.peek().litToBoolean
          if (wen) mem(addr) = wdata else mem.getOrElseUpdate(addr, 0L)
          dbPending = Some(Pending(addr, dut.io.dbus.req.bits.user.peek().litValue, 2))
        }

        // ---- pending release on resp fire ----
        if (dut.io.ibus.resp.valid.peek().litToBoolean && dut.io.ibus.resp.ready.peek().litToBoolean) {
          ibPending = ibPending.tail
        }
        if (dut.io.dbus.resp.valid.peek().litToBoolean && dut.io.dbus.resp.ready.peek().litToBoolean) {
          dbPending = None
        }

        // ---- countdown ----
        ibPending = ibPending.map(p => p.copy(countdown = (p.countdown - 1).max(0)))
        dbPending = dbPending.map(p => p.copy(countdown = (p.countdown - 1).max(0)))

        dut.clock.step()
      }
      assert(commits.size == Expected.size, s"only ${commits.size} commits in $cycle cycles")
      assert(commits.size < 20000, "commit stream did not converge")

      Expected.zip(commits).zipWithIndex.foreach { case ((exp, got), i) =>
        assert(got._1 == exp._1, s"commit $i next_pc: got 0x${got._1.toHexString} expected 0x${exp._1.toHexString}")
        assert(got._2 == exp._2, s"commit $i rd_index: got ${got._2} expected ${exp._2}")
        if (exp._2 != 0) {
          assert(got._3 == exp._3, s"commit $i rd_value: got ${got._3} expected ${exp._3}")
        }
      }
      assert(mem(8L) == 102L, "store did not reach memory")
    }
  }

}