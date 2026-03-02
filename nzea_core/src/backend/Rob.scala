package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}
import nzea_core.frontend.FuType

/** ROB entry state: Executing -> (ALU/BRU/SYSU: Done; AGU: WaitingForMem) -> (LSU load: Done via MemUnit). */
object RobState extends chisel3.ChiselEnum {
  val Executing      = Value  // just entered ROB
  val WaitingForMem  = Value  // AGU done, waiting for MemUnit
  val WaitingForResult = Value  // reserved
  val Done           = Value  // FU complete, ready to commit
}

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

/** Head output: reconstructed from head slot + fu_type/rob_state arrays. */
class RobHead(depth: Int) extends Bundle {
  val rob_id       = UInt(chisel3.util.log2Ceil(depth.max(2)).W)
  val fu_type      = FuType()
  val rob_state    = RobState()
  val rd_index     = UInt(5.W)
  val pred_next_pc = UInt(32.W)
}

/** ROB entry state update: rob_id + new_state. Used by FUs to update ROB via entryAccessPort. */
class RobEntryStateUpdate(idWidth: Int) extends Bundle {
  val rob_id    = UInt(idWidth.W)
  val new_state = RobState()
}

/** Factory for FU to create state-update output. FU calls this to produce rob_entry_access. */
object Rob {
  def entryStateUpdate(valid: Bool, rob_id: UInt, new_state: RobState.Type)(idWidth: Int): Valid[RobEntryStateUpdate] = {
    val w = Wire(Valid(new RobEntryStateUpdate(idWidth)))
    w.valid := valid
    w.bits.rob_id := rob_id
    w.bits.new_state := new_state
    w
  }
}

/** Rob: depth-entry circular buffer. rob_id = stable buffer address.
  * numAccessPorts: number of entry-access ports; use io.accessPorts(i) to connect FU write-back. */
class Rob(depth: Int, numAccessPorts: Int = 5) extends Module {
  require(depth >= 1, "Rob depth must >= 1")
  require(numAccessPorts >= 1, "Rob numAccessPorts must >= 1")
  private val idWidth = chisel3.util.log2Ceil(depth.max(2))

  /** Type for entry access port. FUs use Rob.entryStateUpdate() to create output of this type. */
  def entryAccessPortType = Valid(new RobEntryStateUpdate(idWidth))

  val io = IO(new Bundle {
    val enq          = Flipped(Decoupled(new RobEnqPayload))
    val enq_rob_id   = Output(UInt(idWidth.W))
    val deq          = Decoupled(new RobHead(depth))
    val flush        = Input(Bool())
    val pending_rd   = Output(UInt(32.W))
    val accessPorts  = Vec(numAccessPorts, Flipped(Valid(new RobEntryStateUpdate(idWidth))))
  })

  val head_ptr = RegInit(0.U(idWidth.W))
  val tail_ptr = RegInit(0.U(idWidth.W))
  val count    = RegInit(0.U((idWidth + 1).W))
  val full     = count === depth.U
  val empty    = count === 0.U
  io.enq_rob_id := tail_ptr
  io.enq.ready := !full

  val emptySlot = Wire(Valid(new RobEntry))
  emptySlot.valid := false.B
  emptySlot.bits.rd_index := 0.U
  emptySlot.bits.pred_next_pc := 0.U
  val slots         = RegInit(VecInit(Seq.fill(depth)(emptySlot)))
  val fu_type_vec   = RegInit(VecInit(Seq.fill(depth)(FuType.ALU)))
  val rob_state_vec = RegInit(VecInit(Seq.fill(depth)(RobState.Executing)))

  val head_slot = slots(head_ptr)
  val head_bits = head_slot.bits
  val head_fu   = fu_type_vec(head_ptr)
  val head_state = rob_state_vec(head_ptr)

  io.deq.valid := head_slot.valid
  io.deq.bits.rob_id := head_ptr
  io.deq.bits.fu_type := head_fu
  io.deq.bits.rob_state := head_state
  io.deq.bits.rd_index := head_bits.rd_index
  io.deq.bits.pred_next_pc := head_bits.pred_next_pc

  when(io.flush) {
    head_ptr := 0.U
    tail_ptr := 0.U
    count := 0.U
    for (i <- 0 until depth) {
      slots(i).valid := false.B
      fu_type_vec(i) := FuType.ALU
      rob_state_vec(i) := RobState.Executing
    }
  }.otherwise {
    for (p <- io.accessPorts) {
      when(p.valid) {
        rob_state_vec(p.bits.rob_id) := p.bits.new_state
      }
    }
    when(io.enq.fire) {
      slots(tail_ptr).valid := true.B
      slots(tail_ptr).bits.rd_index := io.enq.bits.rd_index
      slots(tail_ptr).bits.pred_next_pc := io.enq.bits.pred_next_pc
      fu_type_vec(tail_ptr) := io.enq.bits.fu_type
      rob_state_vec(tail_ptr) := RobState.Executing
      tail_ptr := (tail_ptr + 1.U)(idWidth - 1, 0)
    }
    when(io.deq.fire) {
      slots(head_ptr).valid := false.B
      head_ptr := (head_ptr + 1.U)(idWidth - 1, 0)
    }
    count := count + Mux(io.enq.fire, 1.U, 0.U) - Mux(io.deq.fire, 1.U, 0.U)
  }

  val pending_rd = RegInit(0.U(32.W))
  val other_same_rd = (0 until depth).map { i =>
    slots(i).valid && slots(i).bits.rd_index === head_bits.rd_index && head_ptr =/= i.U
  }.reduce(_ || _) || (io.enq.fire && io.enq.bits.rd_index === head_bits.rd_index)
  val to_remove = Mux(
    io.deq.fire && !other_same_rd,
    (1.U(32.W) << head_bits.rd_index),
    0.U(32.W)
  )
  val to_add = Mux(io.enq.fire, (1.U(32.W) << io.enq.bits.rd_index), 0.U(32.W))
  val flush_r = RegNext(io.flush)
  pending_rd := Mux(flush_r, 0.U(32.W), (pending_rd & ~to_remove) | to_add)
  io.pending_rd := pending_rd
}
