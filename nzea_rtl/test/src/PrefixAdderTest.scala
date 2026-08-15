package nzea_rtl

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

/** Combinational harness: s = PrefixAdder(a, b, cin). */
class PrefixAdderHarness(width: Int) extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(width.W))
    val b   = Input(UInt(width.W))
    val cin = Input(Bool())
    val s   = Output(UInt(width.W))
  })
  io.s := PrefixAdder(io.a, io.b, io.cin)
}

/** Combinational harness: s = a - b via the a + ~b + 1 idiom. */
class PrefixSubHarness(width: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val s = Output(UInt(width.W))
  })
  io.s := PrefixAdder(io.a, ~io.b, true.B)
}

/** PrefixAdder must match plain addition for arbitrary operands (including carry-in),
  * and support the a + ~b + 1 subtraction idiom used by the ALU.
  * All checks share one harness (chiseltest compiles per-test, so poke repeatedly).
  */
class PrefixAdderTest extends AnyFlatSpec with ChiselScalatestTester {

  "PrefixAdder" should "match reference add for boundaries, carries, random operands and widths" in {
    test(new PrefixAdderHarness(64)) { dut =>
      val mask32 = (BigInt(1) << 32) - 1
      val rng = new Random(0xADD)
      var cases32: Seq[(BigInt, BigInt)] = Seq(
        (BigInt(0), BigInt(0)),
        (BigInt(0xffffffffL), BigInt(0)),
        (BigInt(0xffffffffL), BigInt(1)),
        (BigInt(0x7fffffffL), BigInt(0x7fffffffL)),
        (BigInt(0x80000000L), BigInt(0x80000000L)),
        (BigInt(0xffffffffL), BigInt(0xffffffffL)),
        (BigInt(0x55555555L), BigInt(0xaaaaaaaaL))
      )
      for (_ <- 0 until 64) {
        cases32 :+= (BigInt(32, rng), BigInt(32, rng))
      }
      for (cin <- Seq(false, true); (a, b) <- cases32) {
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.io.cin.poke(cin.B)
        // 64-bit adder: expect the full-width sum; the low 32 bits equal the
        // truncated 32-bit result exercised by the original 32-bit ALU usage.
        val expected = a + b + (if (cin) 1 else 0)
        dut.io.s.expect(expected.U)
      }
      // Small operands on the 64-bit adder: full-width sum (no truncation expected).
      for (_ <- 0 until 8) {
        val a = BigInt(33, new Random(99))
        val b = BigInt(33, new Random(101))
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.io.cin.poke(false.B)
        dut.io.s.expect((a + b).U)
      }
    }
  }

  it should "work for non-power-of-two widths (3 and 33 bits)" in {
    for (w <- Seq(3, 33)) {
      test(new PrefixAdderHarness(w)) { dut =>
        val mw = (BigInt(1) << w) - 1
        val rng = new Random(w * 31)
        for (_ <- 0 until 16) {
          val a = BigInt(w, rng) & mw
          val b = BigInt(w, rng) & mw
          val cin = rng.nextBoolean()
          dut.io.a.poke(a.U)
          dut.io.b.poke(b.U)
          dut.io.cin.poke(cin.B)
          dut.io.s.expect(((a + b + (if (cin) 1 else 0)) & mw).U)
        }
      }
    }
  }

  it should "match subtraction idiom a + ~b + 1 = a - b" in {
    test(new PrefixSubHarness(32)) { dut =>
      val mask32 = (BigInt(1) << 32) - 1
      val rng = new Random(0x5AB)
      var cases: Seq[(BigInt, BigInt)] = Seq(
        (BigInt(5), BigInt(2)),
        (BigInt(2), BigInt(5)),
        (BigInt(0), BigInt(0xffffffffL)),
        (BigInt(0xffffffffL), BigInt(0xffffffffL)),
        (BigInt(0x80000000L), BigInt(1)),
        (BigInt(0), BigInt(1))
      )
      for (_ <- 0 until 32) {
        cases :+= (BigInt(32, rng), BigInt(32, rng))
      }
      for ((a, b) <- cases) {
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.io.s.expect(((a - b) & mask32).U)
      }
    }
  }
}
