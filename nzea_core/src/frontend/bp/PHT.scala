package nzea_core.frontend.bp

import chisel3._
import chisel3.util._

/** Classic 2-bit saturating-counter direction predictor indexed by PC.
  *
  * A branch's counter saturates toward taken/not-taken as it resolves; the
  * high bit of the counter is the prediction. The update is a two-cycle
  * read-modify-write through `readWrite` with a 1-deep pending queue (updates
  * arriving while the RMW is in flight are dropped).
  */
class PHT(size: Int) extends Module {
  require(size > 0 && (size & (size - 1)) == 0, "PHT size must be power of 2")
  private val indexBits = log2Ceil(size)

  val io = IO(new Bundle {
    val pc           = Input(UInt(32.W))
    val pred_taken   = Output(Bool())
    val update       = Input(Bool())
    val update_pc    = Input(UInt(32.W))
    val update_taken = Input(Bool())
  })

  private val index        = io.pc(indexBits + 1, 2)
  private val update_index = io.update_pc(indexBits + 1, 2)

  val mem = SyncReadMem(size, UInt(2.W), SyncReadMem.WriteFirst)

  val pred_val = mem.read(index, true.B)
  io.pred_taken := pred_val(1)

  val pending_valid   = RegInit(false.B)
  val pending_has_val = RegInit(false.B)
  val pending_index   = Reg(UInt(indexBits.W))
  val pending_taken   = Reg(Bool())
  val pending_old_val = Reg(UInt(2.W))

  val update_addr   = Mux(pending_has_val, pending_index, Mux(pending_valid, pending_index, update_index))
  val update_wrdata = Mux(
    pending_taken,
    Mux(pending_old_val === 3.U, 3.U, pending_old_val + 1.U),
    Mux(pending_old_val === 0.U, 0.U, pending_old_val - 1.U)
  )
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
