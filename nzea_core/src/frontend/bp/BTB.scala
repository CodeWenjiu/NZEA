package nzea_core.frontend.bp

import chisel3._

/** Branch Target Buffer: direct-mapped cache for jump target.
  * Has tag (PC bits). Store tag + target. No valid bit.
  * Update only when actual jump occurs.
  */
class BTB(size: Int) extends Module {
  require(size > 0 && (size & (size - 1)) == 0, "BTB size must be power of 2")
  private val indexBits = chisel3.util.log2Ceil(size)
  private val tagBits   = 32 - indexBits - 2

  val io = IO(new Bundle {
    val pc             = Input(UInt(32.W))
    val pred_target    = Output(UInt(32.W))
    val pred_hit       = Output(Bool())  // tag match
    val update         = Input(Bool())  // only true when actual jump
    val update_pc      = Input(UInt(32.W))
    val update_target  = Input(UInt(32.W))
  })

  private val index         = io.pc(indexBits + 1, 2)
  private val tag           = io.pc(31, indexBits + 2)
  private val update_index  = io.update_pc(indexBits + 1, 2)
  private val update_tag    = io.update_pc(31, indexBits + 2)

  val tags    = Reg(Vec(size, UInt(tagBits.W)))
  val targets = Reg(Vec(size, UInt(32.W)))

  val tag_match = tags(index) === tag
  io.pred_hit := tag_match
  io.pred_target := Mux(tag_match, targets(index), 0.U)

  when(io.update) {
    tags(update_index)    := update_tag
    targets(update_index) := io.update_target
  }
}
