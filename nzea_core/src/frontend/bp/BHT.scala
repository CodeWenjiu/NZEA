package nzea_core.frontend.bp

import chisel3._

/** Branch History Table: 2-bit saturating counter for branch direction.
  * Direct-mapped, no tag. Index from PC[indexBits+1:2].
  * 2-bit: MSB=1 predict jump, MSB=0 predict no jump. Saturate at 0b00 and 0b11.
  * Uses SyncReadMem (1-cycle read latency). Caller must pass pred_next_pc as pc so
  * pred_taken aligns with the fetched PC. Update is pipelined: read at N, write at N+1.
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

  val mem = SyncReadMem(size, UInt(2.W))

  val pred_val = mem.read(index, true.B)
  io.pred_taken := pred_val(1)

  // Update pipeline: SyncReadMem read has 1-cycle latency. Read at N, use result and write at N+1.
  val update_read = mem.read(update_index, io.update)
  val pending_valid = RegInit(false.B)
  val pending_index  = Reg(UInt(indexBits.W))
  val pending_taken  = Reg(Bool())

  when(pending_valid) {
    val old_val = update_read
    val new_val = Mux(pending_taken,
      Mux(old_val === 3.U, 3.U, old_val + 1.U),
      Mux(old_val === 0.U, 0.U, old_val - 1.U))
    mem.write(pending_index, new_val)
    pending_valid := io.update
    when(io.update) {
      pending_index := update_index
      pending_taken := io.update_taken
    }
  }.elsewhen(io.update) {
    pending_valid := true.B
    pending_index := update_index
    pending_taken := io.update_taken
  }
}
