package nzea_core.frontend.bp

import chisel3._

/** BTB entry: tag + target in one SRAM word. */
class BTBEntry(tagBits: Int) extends Bundle {
  val tag    = UInt(tagBits.W)
  val target = UInt(32.W)
}

/** Branch Target Buffer: direct-mapped cache for jump target.
  * Has tag (PC bits). Store tag + target. No valid bit.
  * Update only when actual jump occurs.
  * Uses single SyncReadMem (1-cycle read latency).
  */
class BTB(size: Int) extends Module {
  require(size > 0 && (size & (size - 1)) == 0, "BTB size must be power of 2")
  private val indexBits = chisel3.util.log2Ceil(size)
  private val tagBits   = 32 - indexBits - 2

  val io = IO(new Bundle {
    val read_addr      = Input(UInt(32.W))
    val pc_for_tag     = Input(UInt(32.W))
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

  val mem = SyncReadMem(size, new BTBEntry(tagBits), SyncReadMem.WriteFirst)
  val entry = mem.read(index, true.B)
  val tag_match = entry.tag === tag
  io.pred_hit    := tag_match
  io.pred_target := Mux(tag_match, entry.target, 0.U)

  when(io.update) {
    val wdata = Wire(new BTBEntry(tagBits))
    wdata.tag    := update_tag
    wdata.target := io.update_target
    mem.write(update_index, wdata)
  }
}
