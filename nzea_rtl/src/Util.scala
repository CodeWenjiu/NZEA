package nzea_rtl

import chisel3._
import chisel3.util.PriorityEncoder

/** Tree mux: O(log N) depth for indexed read. Use for large Vec/Seq to improve timing. */
object MuxTree {
  def apply[T <: Data](idx: UInt, data: Seq[T]): T = {
    if (data.size == 1) data.head
    else {
      val half = data.size / 2
      val sel  = idx(chisel3.util.log2Ceil(data.size) - 1)
      Mux(sel, apply(idx, data.drop(half)), apply(idx, data.take(half)))
    }
  }
}

/** Kogge-Stone prefix adder: O(log2 N) carry depth instead of ripple-carry.
  *  s = a + b + cin. Use for wide adds on the critical path (e.g. ALU operands),
  *  where a ripple-carry chain (bit 0 -> bit N-1) would dominate the cycle.
  */
object PrefixAdder {
  def apply(a: UInt, b: UInt, cin: Bool = false.B): UInt = {
    val n = a.getWidth
    require(b.getWidth == n, s"operand widths differ: ${a.getWidth} vs ${b.getWidth}")
    require(n >= 2, s"PrefixAdder needs width >= 2, got $n")
    // Level 0: per-bit generate (g) and propagate (p).
    // OR-based propagate (classic Kogge-Stone): the prefix network is pure
    // AND-OR and maps well in abc; XOR-based propagate blew up to 16 logic
    // levels (abc expands XORs into NAND chains). The half-sum keeps the
    // single XOR per bit for the final sum.
    var g = a & b
    val h0 = a ^ b // half-sum: sum[k] = h0[k] ^ carry[k]
    var p = a | b
    // Merge cin into the level-0 generate at bit 0 so it propagates through the
    // prefix network (a bare `sum bit0 ^ cin` would leave higher carries wrong).
    g = g | (p & chisel3.util.Cat(0.U((n - 1).W), cin))
    // Prefix network: after level i, g[k]/p[k] cover the [k-2^i+1 .. k] span.
    // Shifting right by d brings span coverage: gPrev[k] = g[k-d] (0 for k < d),
    // pPrev[k] = p[k-d] but identity (1) for k < d so p[k] keeps its own span.
    for (i <- 0 until chisel3.util.log2Ceil(n)) {
      val d = 1 << i
      val gPrev = (g << d)(n - 1, 0)
      val pPrev = ((p << d) | ((BigInt(1) << d) - 1).U(n.W))(n - 1, 0)
      g = g | (p & gPrev)
      p = p & pPrev
    }
    // Carry into bit k is g[k-1]; bit 0 takes the explicit cin.
    h0 ^ chisel3.util.Cat(g(n - 2, 0), cin)
  }
}

/** Tree-structured PriorityEncoder: O(log N) depth instead of O(N) linear chain.
  * Returns index of first 1 (LSB), or 0 when all zeros. Use for large free-list bitmaps.
  * Result width = log2Ceil(width) to correctly represent 0..(width-1).
  */
object PriorityEncoderTree {
  def apply(bits: UInt): UInt = apply(bits, bits.getWidth)
  def apply(bits: UInt, width: Int): UInt = {
    require(width >= 1 && width <= bits.getWidth)
    val resultWidth = chisel3.util.log2Ceil(width)
    if (width <= 4) {
      PriorityEncoder(bits(width - 1, 0))
    } else {
      val half = width / 2
      val low  = bits(half - 1, 0)
      val high = bits(width - 1, half)
      val lowHasOne = low.orR
      val lowIdx  = apply(low, half)
      val highIdx = apply(high, width - half)
      val sum = half.U(resultWidth.W) + highIdx
      Mux(lowHasOne, lowIdx.pad(resultWidth), sum)
    }
  }
}
