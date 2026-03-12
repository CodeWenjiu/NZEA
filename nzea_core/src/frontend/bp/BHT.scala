package nzea_core.frontend.bp

import chisel3._
import firrtl.ir.ReadUnderWrite

/** Branch History Table: 2-bit saturating counter for branch direction.
  * Direct-mapped, no tag. Index from PC[indexBits+1:2].
  * 2-bit: MSB=1 predict jump, MSB=0 predict no jump. Saturate at 0b00 and 0b11.
  * Uses SyncReadMem (1-cycle read latency). Caller must pass pred_next_pc as pc so
  * pred_taken aligns with the fetched PC.
  * Update: 1 read port (prediction) + 1 readWrite port (update) for BRAM inference.
  * Accepts only one update at a time; blocks new updates until current completes.
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

  val mem = SyncReadMem(size, UInt(2.W), SyncReadMem.WriteFirst)

  // Port 1: read-only for prediction
  val pred_val = mem.read(index, true.B)
  io.pred_taken := pred_val(1)

  // Port 2: readWrite for update. Pipeline: read at N, capture at N+1, write at N+2.
  val pending_valid   = RegInit(false.B)
  val pending_has_val = RegInit(false.B)  // have old_val, will write
  val pending_index   = Reg(UInt(indexBits.W))
  val pending_taken   = Reg(Bool())
  val pending_old_val = Reg(UInt(2.W))

  val update_addr   = Mux(pending_has_val, pending_index, Mux(pending_valid, pending_index, update_index))
  val update_wrdata = Mux(pending_taken,
    Mux(pending_old_val === 3.U, 3.U, pending_old_val + 1.U),
    Mux(pending_old_val === 0.U, 0.U, pending_old_val - 1.U))
  val update_en   = pending_valid || pending_has_val || io.update
  val update_is_wr = pending_has_val
  val update_rd   = mem.readWrite(update_addr, update_wrdata, update_en, update_is_wr)

  when(pending_has_val) {
    pending_valid   := false.B
    pending_has_val := false.B
  }.elsewhen(pending_valid) {
    pending_old_val := update_rd
    pending_has_val := true.B
  }.elsewhen(io.update && !pending_valid && !pending_has_val) {
    pending_valid := true.B
    pending_index := update_index
    pending_taken := io.update_taken
  }
}
