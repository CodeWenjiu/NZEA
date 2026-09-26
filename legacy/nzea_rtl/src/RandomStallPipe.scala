package nzea_rtl

import chisel3._
import chisel3.util._

/** Single-entry pipe with MCG-driven random backpressure.
  *
  * While `valid` holds a transfer, a random source advances each cycle. The transfer proceeds only when `combined <
  * threshold`, yielding a geometric stall distribution with **expected interval = `expectedCycles`**.
  *
  * @param expectedCycles
  *   target average cycles-per-transfer (>= 1.0, fractional OK)
  * @param lfsrWidth
  *   random source width (default 16)
  */
class RandomStallPipe[T <: Data](gen: T, expectedCycles: Double, lfsrWidth: Int = 16) extends Module {
  require(expectedCycles >= 1.0, s"expectedCycles=$expectedCycles must be >= 1.0")

  // E = 1 + 1/p  →  p = 1/(E-1).  Min interval is 2 cycles (fill+drain).
  // Threshold = 2^W / (E-1), clamped to [1, 2^W-1].
  // Computed inline at the point of use to avoid any Long→UInt conversion
  // issues in the FIRRTL emission.

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(gen))
    val out = Decoupled(gen)
    val flush = Input(Bool())
  })

  // ── Random source: MCG XOR counter ──
  private val mcga = RegInit(1.U(lfsrWidth.W))
  private val mcgaNext = (mcga << 2) + mcga + 1.U // 5x+1, full period
  mcga := mcgaNext
  private val ctr = RegInit(0.U(lfsrWidth.W))
  ctr := ctr + 1.U
  private val combined = Cat(mcga(7, 0), mcga(15, 8)) ^ ctr

  // Compute threshold: p = 1/(E-1), T = round(p * 2^W), clamped to [1, 2^W-1].
  private val thresh: Int = {
    val numer = 1L << lfsrWidth
    if (expectedCycles <= 1.0001) (numer - 1).toInt
    else ((numer.toDouble / (expectedCycles - 1.0)).round.toInt).min((numer - 1).toInt).max(1)
  }

  private val lfsrPass = combined < thresh.U

  // ── Single-entry buffer ──
  private val valid = RegInit(false.B)
  private val bits = Reg(gen)

  io.in.ready := !valid
  io.out.valid := valid && lfsrPass
  io.out.bits := bits

  when(io.flush) { valid := false.B }
    .elsewhen(io.in.fire) { valid := true.B; bits := io.in.bits }

  when(io.out.fire) { valid := false.B }
}
