package nzea_core.rename

import chisel3._
import chisel3.util.{log2Ceil, Valid}

/** Free List: FIFO of free physical registers. PR0~31 initially in use, PR32~(depth-1) free.
  * Supports checkpoint for flush recovery.
  */
class FreeList(depth: Int) extends Module {
  require(depth >= 32)
  private val addrWidth = log2Ceil(depth)
  private val freeCount = depth - 32
  private val idxWidth  = log2Ceil(freeCount)

  val io = IO(new Bundle {
    val pop  = Flipped(Valid(Bool()))
    val pr   = Output(Valid(UInt(addrWidth.W)))
    val push = Input(Valid(UInt(addrWidth.W)))
    val empty = Output(Bool())
    val checkpoint = new Bundle {
      val snapshot = Input(Bool())
      val restore  = Input(Bool())
    }
  })

  val buf    = RegInit(VecInit(Seq.tabulate(freeCount)(i => (32 + i).U(addrWidth.W))))
  val head   = RegInit(0.U((idxWidth + 1).W))
  val tail   = RegInit(freeCount.U((idxWidth + 1).W))
  val buf_cp = Reg(Vec(freeCount, UInt(addrWidth.W)))
  val head_cp = Reg(UInt((idxWidth + 1).W))
  val tail_cp = Reg(UInt((idxWidth + 1).W))

  when(io.checkpoint.snapshot) {
    for (i <- 0 until freeCount) { buf_cp(i) := buf(i) }
    head_cp := head
    tail_cp := tail
  }
  when(io.checkpoint.restore) {
    for (i <- 0 until freeCount) { buf(i) := buf_cp(i) }
    head := head_cp
    tail := tail_cp
  }.otherwise {
    when(io.push.valid && io.push.bits =/= 0.U) {
      buf(tail(idxWidth - 1, 0)) := io.push.bits
      tail := tail + 1.U
    }
    when(io.pop.valid && io.pop.bits && head =/= tail) {
      head := head + 1.U
    }
  }

  io.pr.valid := io.pop.valid && io.pop.bits && head =/= tail
  io.pr.bits  := buf(head(idxWidth - 1, 0))
  io.empty    := head === tail
}
