package nzea_core.frontend.bp

import chisel3._

/** Branch Target Buffer: direct-mapped cache for jump target.
  * Has tag (PC bits). Store tag + target. No valid bit.
  * Update only when actual jump occurs.
  * Uses SyncReadMem (1-cycle read latency). read_addr = pc to look up index(pc)
  * for current fetch. No combinational cycle since pc is from Reg.
  * Update is direct write (no read-modify-write).
  */
class BTB(size: Int) extends Module {
  require(size > 0 && (size & (size - 1)) == 0, "BTB size must be power of 2")
  private val indexBits = chisel3.util.log2Ceil(size)
  private val tagBits   = 32 - indexBits - 2

  val io = IO(new Bundle {
    val read_addr      = Input(UInt(32.W))  // pc: addr to read (current fetch)
    val pc_for_tag     = Input(UInt(32.W))  // pc: for tag compare
    val pred_target    = Output(UInt(32.W))
    val pred_hit       = Output(Bool())
    val update         = Input(Bool())
    val update_pc      = Input(UInt(32.W))
    val update_target  = Input(UInt(32.W))
  })

  private val index         = io.read_addr(indexBits + 1, 2)
  private val tag           = io.pc_for_tag(31, indexBits + 2)
  private val update_index  = io.update_pc(indexBits + 1, 2)
  private val update_tag    = io.update_pc(31, indexBits + 2)

  val tags_mem    = SyncReadMem(size, UInt(tagBits.W))
  val targets_mem = SyncReadMem(size, UInt(32.W))

  val pred_tag         = tags_mem.read(index, true.B)
  val pred_target_val  = targets_mem.read(index, true.B)
  val tag_match        = pred_tag === tag
  io.pred_hit          := tag_match
  io.pred_target       := Mux(tag_match, pred_target_val, 0.U)

  when(io.update) {
    tags_mem.write(update_index, update_tag)
    targets_mem.write(update_index, io.update_target)
  }
}
