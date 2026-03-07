package nzea_core

import chisel3._

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
