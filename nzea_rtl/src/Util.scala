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
