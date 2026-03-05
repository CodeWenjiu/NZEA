package nzea_core.retire.rob

import chisel3._
import chisel3.util.{Decoupled, Mux1H, PriorityEncoder, Valid}
import nzea_core.backend.LsuOp
import nzea_core.retire.CommitMsg

/** Companion object: entryStateUpdate helper and apply for wiring. */
object Rob {
  /** Create Rob and wire fuOutputs; call from parent (e.g. Core) so connection context is correct. */
  def apply(depth: Int, fuOutputs: Seq[RobAccessIO]): Rob = {
    val r = Module(new Rob(depth, fuOutputs.size))
    (r.io.accessPorts zip fuOutputs).foreach { case (p, fu) =>
      p.valid := fu.valid
      p.bits := fu.bits
      fu.ready := p.ready
      fu.flush := p.flush
    }
    r
  }

  def entryStateUpdate(
    valid: Bool,
    rob_id: UInt,
    new_state: RobState.Type,
    rd_value: UInt,
    flush: Bool = false.B,
    next_pc: UInt = 0.U,
    mem_wdata: UInt = 0.U,
    mem_wstrb: UInt = 0.U,
    mem_lsuOp: nzea_core.backend.LsuOp.Type = nzea_core.backend.LsuOp.LB
  )(idWidth: Int): chisel3.util.Valid[RobEntryStateUpdate] = {
    val w = Wire(chisel3.util.Valid(new RobEntryStateUpdate(idWidth)))
    w.valid := valid
    w.bits.rob_id := rob_id
    w.bits.new_state := new_state
    w.bits.rd_value := rd_value
    w.bits.flush := flush
    w.bits.next_pc := next_pc
    w.bits.mem_wdata := mem_wdata
    w.bits.mem_wstrb := mem_wstrb
    w.bits.mem_lsuOp := mem_lsuOp
    w
  }
}

/** Rob: depth-entry circular buffer. rob_id = stable slot index.
  * Use Rob.apply(depth, fuOutputs) to create and wire in one call.
  */
class Rob(depth: Int, numAccessPorts: Int) extends Module {
  require(depth >= 1, "Rob depth must >= 1")
  require(numAccessPorts >= 1, "Rob numAccessPorts must >= 1")

  private val idWidth = chisel3.util.log2Ceil(depth.max(2))

  // IO interfaces
  val enq = IO(new RobEnqIO(idWidth))
  val mem = IO(new RobMemIO(idWidth))
  val io = IO(new Bundle {
    val commit        = Valid(new CommitMsg)
    val accessPorts   = Vec(numAccessPorts, Flipped(new RobAccessIO(idWidth)))
    val slotReadRs1   = new RobSlotReadPort(idWidth)
    val slotReadRs2   = new RobSlotReadPort(idWidth)
    val rat_rob_write = Output(Valid(new Bundle { val rd_index = UInt(5.W); val rob_id = UInt(idWidth.W) }))
  })

  // -------- Pointers & Slots --------

  val head_ptr = RegInit(0.U(idWidth.W))
  val tail_ptr = RegInit(0.U(idWidth.W))
  val count   = RegInit(0.U((idWidth + 1).W))

  def idxFromHead(offset: UInt): UInt = (head_ptr + offset)(idWidth - 1, 0)

  def emptyEntry: RobEntry = {
    val e = Wire(new RobEntry)
    e.rd_index := 0.U
    e.rob_state := RobState.Executing
    e.rd_value := 0.U
    e.next_pc := 0.U
    e.flush := false.B
    e.mem_wdata := 0.U
    e.mem_wstrb := 0.U
    e.mem_lsuOp := LsuOp.LB
    e
  }

  val slots = RegInit(VecInit(Seq.fill(depth)({
    val v = Wire(Valid(new RobEntry))
    v.valid := false.B
    v.bits := emptyEntry
    v
  })))

  val head_slot = slots(head_ptr)
  val head_bits = head_slot.bits

  // -------- Enq --------

  val full = count === depth.U
  enq.rob_id := tail_ptr
  enq.req.ready := !full

  // -------- Commit --------

  val head_done = head_slot.valid && head_bits.rob_state === RobState.Done
  val do_flush  = io.commit.valid && head_bits.flush

  io.commit.valid := head_done
  io.commit.bits.next_pc  := head_bits.next_pc
  io.commit.bits.rd_index := head_bits.rd_index
  io.commit.bits.rd_value := head_bits.rd_value

  io.accessPorts.foreach { p =>
    p.ready := true.B
    p.flush := do_flush
  }

  def readSlot(idx: UInt): RobSlotRead = {
    val s = Wire(new RobSlotRead)
    s.valid     := Mux1H((0 until depth).map(i => idx === i.U), slots.map(_.valid))
    s.rob_state := Mux1H((0 until depth).map(i => idx === i.U), slots.map(_.bits.rob_state))
    s.rd_value  := Mux1H((0 until depth).map(i => idx === i.U), slots.map(_.bits.rd_value))
    s
  }
  io.slotReadRs1.slot := readSlot(io.slotReadRs1.rob_id)
  io.slotReadRs2.slot := readSlot(io.slotReadRs2.rob_id)

  io.rat_rob_write.valid := io.commit.valid
  io.rat_rob_write.bits.rd_index := head_bits.rd_index
  io.rat_rob_write.bits.rob_id   := head_ptr

  // -------- Submodules --------

  val memReqManager = Module(new MemReqManager(depth, idWidth))

  // Connect Memory Request Manager
  memReqManager.io.mem <> mem
  memReqManager.io.flush := do_flush
  memReqManager.io.headPtr := head_ptr
  memReqManager.io.count := count
  memReqManager.io.slotsValid := slots.map(_.valid)
  memReqManager.io.slotsState := slots.map(_.bits.rob_state)
  memReqManager.io.slotsAddr := slots.map(_.bits.rd_value)
  memReqManager.io.slotsWdata := slots.map(_.bits.mem_wdata)
  memReqManager.io.slotsWstrb := slots.map(_.bits.mem_wstrb)
  memReqManager.io.slotsLsuOp := slots.map(_.bits.mem_lsuOp)

  // -------- Main Update Logic --------

  when(do_flush) {
    head_ptr := 0.U
    tail_ptr := 0.U
    count := 0.U
    for (i <- 0 until depth) {
      slots(i).valid := false.B
      slots(i).bits := emptyEntry
    }
  }.otherwise {
    applyFuUpdates()
    applyMemSlotUpdates()
    applyEnq()
    applyCommit()
    updateCount()
  }

  def applyMemSlotUpdates(): Unit = {
    when(mem.req.fire) {
      slots(mem.req.bits.rob_id).bits.rob_state := RobState.WaitingForResult
    }
    when(mem.resp.fire) {
      val respSlot = slots(mem.resp.bits.rob_id)
      respSlot.bits.rob_state := RobState.Done
      val isLoad = respSlot.bits.mem_lsuOp =/= LsuOp.SB &&
                   respSlot.bits.mem_lsuOp =/= LsuOp.SH &&
                   respSlot.bits.mem_lsuOp =/= LsuOp.SW
      when(isLoad) {
        respSlot.bits.rd_value := mem.resp.bits.data
      }
    }
  }

  def applyFuUpdates(): Unit = {
    for (p <- io.accessPorts) {
      when(p.valid) {
        val slot = slots(p.bits.rob_id)
        slot.bits.rob_state := p.bits.new_state
        when(p.bits.new_state === RobState.Done) {
          slot.bits.rd_value := p.bits.rd_value
        }
        when(p.bits.flush) {
          slot.bits.flush := true.B
          slot.bits.next_pc := p.bits.next_pc
        }
        when(p.bits.new_state === RobState.WaitingForMem) {
          slot.bits.rd_value  := p.bits.rd_value
          slot.bits.mem_wdata := p.bits.mem_wdata
          slot.bits.mem_wstrb := p.bits.mem_wstrb
          slot.bits.mem_lsuOp := p.bits.mem_lsuOp
        }
      }
    }
  }

  def applyEnq(): Unit = {
    when(enq.req.fire) {
      val slot = slots(tail_ptr)
      slot.valid := true.B
      slot.bits.rd_index  := enq.req.bits.rd_index
      slot.bits.rob_state := RobState.Executing
      slot.bits.rd_value  := 0.U
      slot.bits.next_pc   := enq.req.bits.pred_next_pc
      slot.bits.flush     := false.B
      tail_ptr := (tail_ptr + 1.U)(idWidth - 1, 0)
    }
  }

  def applyCommit(): Unit = {
    when(io.commit.valid) {
      slots(head_ptr).valid := false.B
      head_ptr := (head_ptr + 1.U)(idWidth - 1, 0)
    }
  }

  def updateCount(): Unit = {
    count := count + Mux(enq.req.fire, 1.U, 0.U) - Mux(io.commit.valid, 1.U, 0.U)
  }
}
