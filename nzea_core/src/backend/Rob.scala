package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}
import nzea_core.frontend.FuType

/** One entry in the Rob: fu_type, rd_index, pred_next_pc (predicted; real next_pc from WBU commit_msg). */
class RobEntry extends Bundle {
  val fu_type      = FuType()
  val rd_index     = UInt(5.W)
  val pred_next_pc = UInt(32.W)
}

/** Rob: depth-entry FIFO using shift-register. Head and pending_rd are direct wires (no MUX, better timing). */
class Rob(depth: Int) extends Module {
  require(depth >= 1, "Rob depth must >= 1")

  val io = IO(new Bundle {
    val enq        = Flipped(Decoupled(new RobEntry))
    val deq        = Output(Valid(new RobEntry))  // head; consumer sets commit to deq
    val commit     = Input(Bool())
    val flush      = Input(Bool())  // mispredict: clear all entries (from BRU, 1 cycle delayed)
    val pending_rd = Output(Vec(depth, Valid(UInt(5.W))))
  })

  val slots   = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(Valid(new RobEntry)))))
  val head    = slots(0)
  val full    = slots.map(_.valid).reduce(_ && _)
  val enqSlot = PriorityEncoder(slots.map(s => !s.valid))

  io.deq.valid := head.valid
  io.deq.bits  := head.bits
  io.enq.ready := (!full || io.commit)
  for (i <- 0 until depth) {
    io.pending_rd(i).valid := slots(i).valid
    io.pending_rd(i).bits   := slots(i).bits.rd_index
  }

  when(io.flush) {
    for (i <- 0 until depth) { slots(i).valid := false.B }
  }.elsewhen(io.commit && io.enq.fire) {
    for (i <- 0 until depth - 1) { slots(i) := slots(i + 1) }
    slots(depth - 1).valid := false.B
    val tailSlot = (PopCount(slots.map(_.valid)) - 1.U)(chisel3.util.log2Ceil(depth) - 1, 0)
    slots(tailSlot).valid := true.B
    slots(tailSlot).bits  := io.enq.bits
  }.elsewhen(io.commit && !io.flush) {
    for (i <- 0 until depth - 1) { slots(i) := slots(i + 1) }
    slots(depth - 1).valid := false.B
  }.elsewhen(io.enq.fire && !io.flush) {
    slots(enqSlot).valid := true.B
    slots(enqSlot).bits  := io.enq.bits
  }
}
