package nzea_core.frontend.bp

import chisel3._

/** Branch History Table: 2-bit saturating counter for branch direction.
  * Direct-mapped, no tag. Index from PC[indexBits+1:2].
  * 2-bit: MSB=1 predict jump, MSB=0 predict no jump. Saturate at 0b00 and 0b11.
  * Uses Mem (combinational read) + pipelined update: new_val/update_index registered
  * before write to break high-fanout path. SyncReadMem has 1-cycle read latency and
  * would cause pred_taken to lag by one PC, breaking branch prediction.
  */
class BHT(size: Int) extends Module {
  require(size > 0 && (size & (size - 1)) == 0, "BHT size must be power of 2")
  private val indexBits = chisel3.util.log2Ceil(size)

  val io = IO(new Bundle {
    val pc            = Input(UInt(32.W))
    val pred_taken    = Output(Bool())
    val update        = Input(Bool())
    val update_pc     = Input(UInt(32.W))
    val update_taken  = Input(Bool())
  })

  private val index        = io.pc(indexBits + 1, 2)
  private val update_index = io.update_pc(indexBits + 1, 2)

  val mem = Mem(size, UInt(2.W))

  // Combinational read: pred_taken must match current PC in same cycle.
  val pred_val = mem(index)
  io.pred_taken := pred_val(1)

  // Pipeline update by 1 cycle: register new_val and index before write.
  val pending_valid = RegInit(false.B)
  val pending_index  = Reg(UInt(indexBits.W))
  val pending_newval = Reg(UInt(2.W))

  // Bypass: if update_index == pending_index, use pending_newval as old_val.
  val old_val = Mux(
    io.update && pending_valid && (update_index === pending_index),
    pending_newval,
    mem(update_index)
  )

  when(io.update) {
    val new_val = Mux(io.update_taken,
      Mux(old_val === 3.U, 3.U, old_val + 1.U),
      Mux(old_val === 0.U, 0.U, old_val - 1.U))
    pending_valid := true.B
    pending_index  := update_index
    pending_newval := new_val
  }.elsewhen(pending_valid) {
    pending_valid := false.B
  }

  when(pending_valid) {
    mem.write(pending_index, pending_newval)
  }
}
