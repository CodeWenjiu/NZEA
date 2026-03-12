package nzea_core.frontend.bp

import chisel3._

/** Branch History Table: 2-bit saturating counter for branch direction.
  * Direct-mapped, no tag. Index from PC[indexBits+1:2].
  * 2-bit: MSB=1 predict jump, MSB=0 predict no jump. Saturate at 0b00 and 0b11.
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

  private val index         = io.pc(indexBits + 1, 2)
  private val update_index  = io.update_pc(indexBits + 1, 2)

  val bht = RegInit(VecInit(Seq.fill(size)(0.U(2.W))))

  io.pred_taken := bht(index)(1)

  when(io.update) {
    val old_val = bht(update_index)
    val new_val = Mux(io.update_taken,
      Mux(old_val === 3.U, 3.U, old_val + 1.U),
      Mux(old_val === 0.U, 0.U, old_val - 1.U))
    bht(update_index) := new_val
  }
}
