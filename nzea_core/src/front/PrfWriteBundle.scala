package nzea_core.front

import chisel3._

/** Result write-back port: destination index + data, shared by every FU completion.
  *
  * The baseline in-order engine ties the index to 0 (no register renaming); with renaming it becomes the physical
  * register index.
  */
class PrfWriteBundle(waddrWidth: Int) extends Bundle {
  val waddr = UInt(waddrWidth.W)
  val data = UInt(32.W)
}