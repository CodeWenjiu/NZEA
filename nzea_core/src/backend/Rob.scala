package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}
import nzea_core.frontend.FuType

/** One entry in the Rob: rd_index, pred_next_pc. Instruction identified by rob_id (buffer address). */
class RobEntry extends Bundle {
  val rd_index     = UInt(5.W)
  val pred_next_pc = UInt(32.W)
}

/** Payload for Rob enq: entry data + fu_type (stored in parallel array for commit). */
class RobEnqPayload extends Bundle {
  val rd_index     = UInt(5.W)
  val pred_next_pc = UInt(32.W)
  val fu_type      = FuType()
}

/** Head output: reconstructed from slot 0 + fu_type array. */
class RobHead(depth: Int) extends Bundle {
  val rob_id       = UInt(chisel3.util.log2Ceil(depth.max(2)).W)
  val fu_type      = FuType()
  val rd_index     = UInt(5.W)
  val pred_next_pc = UInt(32.W)
}

/** Rob: depth-entry FIFO using shift-register. rob_id = buffer address, assigned in IS stage. */
class Rob(depth: Int) extends Module {
  require(depth >= 1, "Rob depth must >= 1")
  private val idWidth = chisel3.util.log2Ceil(depth.max(2))

  val io = IO(new Bundle {
    val enq       = Flipped(Decoupled(new RobEnqPayload))
    val enq_rob_id = Output(UInt(idWidth.W)) // allocated rob_id when enq.ready
    val deq       = Decoupled(new RobHead(depth))
    val flush     = Input(Bool())
    val pending_rd = Output(UInt(32.W))
  })

  val emptySlot = Wire(Valid(new RobEntry))
  emptySlot.valid := false.B
  emptySlot.bits.rd_index := 0.U
  emptySlot.bits.pred_next_pc := 0.U
  val slots      = RegInit(VecInit(Seq.fill(depth)(emptySlot)))
  val fu_type_vec = RegInit(VecInit(Seq.fill(depth)(FuType.ALU)))
  val head       = slots(0)
  val full       = slots.map(_.valid).reduce(_ && _)
  val enqSlot   = PriorityEncoder(slots.map(s => !s.valid))
  io.enq_rob_id := enqSlot

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
  io.deq.bits.rob_id := 0.U(idWidth.W)
  io.deq.bits.fu_type := fu_type_vec(0)
  io.deq.bits.rd_index := head.bits.rd_index
  io.deq.bits.pred_next_pc := head.bits.pred_next_pc
  io.enq.ready := (!full || io.deq.fire)

  when(io.flush) {
    for (i <- 0 until depth) {
      slots(i).valid := false.B
      fu_type_vec(i) := FuType.ALU
    }
  }.elsewhen(io.deq.fire && io.enq.fire) {
    for (i <- 0 until depth - 1) {
      slots(i) := slots(i + 1)
      fu_type_vec(i) := fu_type_vec(i + 1)
    }
    slots(depth - 1).valid := false.B
    val tailSlot =
      (PopCount(slots.map(_.valid)) - 1.U)(chisel3.util.log2Ceil(depth) - 1, 0)
    slots(tailSlot).valid := true.B
    slots(tailSlot).bits.rd_index := io.enq.bits.rd_index
    slots(tailSlot).bits.pred_next_pc := io.enq.bits.pred_next_pc
    fu_type_vec(tailSlot) := io.enq.bits.fu_type
  }.elsewhen(io.deq.fire && !io.flush) {
    for (i <- 0 until depth - 1) {
      slots(i) := slots(i + 1)
      fu_type_vec(i) := fu_type_vec(i + 1)
    }
    slots(depth - 1).valid := false.B
  }.elsewhen(io.enq.fire && !io.flush) {
    slots(enqSlot).valid := true.B
    slots(enqSlot).bits.rd_index := io.enq.bits.rd_index
    slots(enqSlot).bits.pred_next_pc := io.enq.bits.pred_next_pc
    fu_type_vec(enqSlot) := io.enq.bits.fu_type
  }
}
