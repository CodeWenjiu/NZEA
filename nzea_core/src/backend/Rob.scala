package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}
import nzea_core.frontend.FuType

/** One entry in the Rob: fu_type (one-hot), rd_index, pred_next_pc (predicted; real
  * next_pc from WBU commit_msg).
  */
class RobEntry extends Bundle {
  val fu_type      = FuType()
  val rd_index     = UInt(5.W)
  val pred_next_pc = UInt(32.W)
}

/** Rob: depth-entry FIFO using shift-register. Head and pending_rd are direct
  * wires (no MUX, better timing).
  */
class Rob(depth: Int) extends Module {
  require(depth >= 1, "Rob depth must >= 1")

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new RobEntry))
    val deq =
      Decoupled(new RobEntry) // head; consumer pulls via ready (= commit)
    val flush =
      Input(Bool()) // mispredict: clear all entries (from BRU, 1 cycle delayed)
    val pending_rd =
      Output(UInt(32.W)) // bit i = 1 if any valid entry writes rd i
  })

  val emptySlot = Wire(Valid(new RobEntry))
  emptySlot.valid := false.B
  emptySlot.bits.fu_type := FuType.ALU
  emptySlot.bits.rd_index := 0.U
  emptySlot.bits.pred_next_pc := 0.U
  val slots = RegInit(VecInit(Seq.fill(depth)(emptySlot)))
  val head = slots(0)
  val full = slots.map(_.valid).reduce(_ && _)
  val enqSlot = PriorityEncoder(slots.map(s => !s.valid))

  // pending_rd: reg-based; bit i = 1 if rd i is written by any valid ROB entry
  val pending_rd = RegInit(0.U(32.W))
  val other_same_rd = (1 until depth)
    .map(i => slots(i).valid && slots(i).bits.rd_index === head.bits.rd_index)
    .reduce(_ || _) ||
    (io.enq.fire && io.enq.bits.rd_index === head.bits.rd_index)
  val to_remove = Mux(
    io.deq.fire && !other_same_rd,
    (1.U(32.W) << head.bits.rd_index),
    0.U(32.W)
  )
  val to_add = Mux(io.enq.fire, (1.U(32.W) << io.enq.bits.rd_index), 0.U(32.W))
  val flush_r = RegNext(io.flush)
  pending_rd := Mux(flush_r, 0.U(32.W), (pending_rd & ~to_remove) | to_add)
  io.pending_rd := pending_rd

  io.deq.valid := head.valid
  io.deq.bits := head.bits
  io.enq.ready := (!full || io.deq.fire)

  when(io.flush) {
    for (i <- 0 until depth) { slots(i).valid := false.B }
  }.elsewhen(io.deq.fire && io.enq.fire) {
    for (i <- 0 until depth - 1) { slots(i) := slots(i + 1) }
    slots(depth - 1).valid := false.B
    val tailSlot =
      (PopCount(slots.map(_.valid)) - 1.U)(chisel3.util.log2Ceil(depth) - 1, 0)
    slots(tailSlot).valid := true.B
    slots(tailSlot).bits := io.enq.bits
  }.elsewhen(io.deq.fire && !io.flush) {
    for (i <- 0 until depth - 1) { slots(i) := slots(i + 1) }
    slots(depth - 1).valid := false.B
  }.elsewhen(io.enq.fire && !io.flush) {
    slots(enqSlot).valid := true.B
    slots(enqSlot).bits := io.enq.bits
  }
}
