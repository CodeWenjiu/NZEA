package nzea_core.rename

import chisel3._
import chisel3.util.{Decoupled, log2Ceil, Valid}

/** Free List: FIFO of free physical registers. PR0~31 initially in use, PR32~(depth-1) free.
  * Checkpoint: updated on commit (push old_p_rd), restored on flush. Independent of branch dispatch.
  * pr: Decoupled — FreeList (producer) drives valid/bits, IDU (consumer) drives ready. Fire = pop.
  */
class FreeList(depth: Int) extends Module {
  require(depth >= 32)
  private val addrWidth = log2Ceil(depth)
  private val freeCount = depth - 32
  private val idxWidth  = log2Ceil(freeCount)

  val io = IO(new Bundle {
    val pr     = Decoupled(UInt(addrWidth.W))
    val push  = Input(Valid(UInt(addrWidth.W)))
    val commit = Input(Valid(UInt(addrWidth.W)))  // old_p_rd to push to checkpoint
    val flush  = Input(Bool())
  })

  val buf     = RegInit(VecInit(Seq.tabulate(freeCount)(i => (32 + i).U(addrWidth.W))))
  val head    = RegInit(0.U((idxWidth + 1).W))
  val tail    = RegInit(freeCount.U((idxWidth + 1).W))
  val buf_cp  = RegInit(VecInit(Seq.tabulate(freeCount)(i => (32 + i).U(addrWidth.W))))
  val head_cp = RegInit(0.U((idxWidth + 1).W))
  val tail_cp = RegInit(freeCount.U((idxWidth + 1).W))

  // Checkpoint: always push on commit (incl. flush) so cp stays in sync for next flush.
  // Reg write takes effect next cycle; same-cycle read of buf_cp/tail_cp sees OLD value.
  when(io.commit.valid && io.commit.bits =/= 0.U) {
    buf_cp(tail_cp(idxWidth - 1, 0)) := io.commit.bits
    tail_cp := tail_cp + 1.U
  }
  when(io.flush) {
    for (i <- 0 until freeCount) { buf(i) := buf_cp(i) }
    head := head_cp
    tail := tail_cp
    // Apply commit combinationally: overwrite buf(tail_cp) and tail
    when(io.commit.valid && io.commit.bits =/= 0.U) {
      buf(tail_cp(idxWidth - 1, 0)) := io.commit.bits
      tail := tail_cp + 1.U
    }
  }.otherwise {
    when(io.push.valid && io.push.bits =/= 0.U) {
      buf(tail(idxWidth - 1, 0)) := io.push.bits
      tail := tail + 1.U
    }
    when(io.pr.fire) {
      head := head + 1.U
    }
  }

  // Reg write takes effect next cycle; flush-cycle outputs must see restore+commit combinationally.
  val tail_eff = Mux(io.flush, Mux(io.commit.valid && io.commit.bits =/= 0.U, tail_cp + 1.U, tail_cp), tail)
  val head_eff = Mux(io.flush, head_cp, head)
  val buf_bits = Mux(
    io.flush,
    buf_cp(head_cp(idxWidth - 1, 0)),
    buf(head(idxWidth - 1, 0))
  )

  io.pr.valid := head_eff =/= tail_eff
  io.pr.bits  := buf_bits
}
