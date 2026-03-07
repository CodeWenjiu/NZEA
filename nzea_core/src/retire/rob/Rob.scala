package nzea_core.retire.rob

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.backend.LsuOp
import nzea_core.retire.CommitMsg

/** Companion object: entryStateUpdate helper and apply for wiring. */
object Rob {
  /** Create Rob and wire fuOutputs; call from parent (e.g. Core) so connection context is correct. */
  def apply(depth: Int, fuOutputs: Seq[RobAccessIO], aguPortIndex: Int = 3): Rob = {
    val r = Module(new Rob(depth, fuOutputs.size, aguPortIndex))
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
    is_done: Bool,
    need_mem: Bool,
    rd_value: UInt,
    flush: Bool = false.B,
    next_pc: UInt = 0.U,
    mem_lsuOp: nzea_core.backend.LsuOp.Type = nzea_core.backend.LsuOp.LB
  )(idWidth: Int): chisel3.util.Valid[RobEntryStateUpdate] = {
    val w = Wire(chisel3.util.Valid(new RobEntryStateUpdate(idWidth)))
    w.valid := valid
    w.bits.rob_id := rob_id
    w.bits.is_done := is_done
    w.bits.need_mem := need_mem
    w.bits.rd_value := rd_value
    w.bits.flush := flush
    w.bits.next_pc := next_pc
    w.bits.mem_lsuOp := mem_lsuOp
    w
  }
}

/** Rob: depth-entry circular buffer. rob_id = stable slot index.
  * MemUnit holds LsBuffer; AGU enqueues there. Rob issues mem request by rob_id; receives resp.
  * Slot fields: rd_index, need_mem, rd_value, mem_lsuOp (for resp).
  */
class Rob(depth: Int, numAccessPorts: Int, aguPortIndex: Int = 3) extends Module {
  require(depth >= 1, "Rob depth must >= 1")
  require(numAccessPorts >= 1, "Rob numAccessPorts must >= 1")
  require(aguPortIndex >= 0 && aguPortIndex < numAccessPorts, "aguPortIndex must be valid")

  private val idWidth  = chisel3.util.log2Ceil(depth.max(2))
  private val ptrWidth = idWidth + 1  // low idWidth bits = physical index, MSB = wrap/lap bit

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

  // -------- Pointers & Split Slot Regs --------
  // Empty/full by wrap bits: ptrWidth-bit ptr, low idWidth bits = physical index, MSB = wrap
  // Empty: head === tail; Full: head(idWidth-1,0) === tail(idWidth-1,0) && head(ptrWidth-1) =/= tail(ptrWidth-1)
  val head_ptr = RegInit(0.U(ptrWidth.W))
  val tail_ptr = RegInit(0.U(ptrWidth.W))
  val head_phys = head_ptr(idWidth - 1, 0)
  val tail_phys = tail_ptr(idWidth - 1, 0)
  val count = (tail_ptr - head_ptr)(ptrWidth - 1, 0)  // combinational, no register

  // Each field in its own Reg; mem addr/wdata/wstrb in LS_Queue
  val slots_rd_index    = RegInit(VecInit(Seq.fill(depth)(0.U(5.W))))
  val slots_is_done     = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val slots_need_mem    = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val slots_rd_value    = RegInit(VecInit(Seq.fill(depth)(0.U(32.W))))
  val slots_next_pc     = RegInit(VecInit(Seq.fill(depth)(0.U(32.W))))
  val slots_flush       = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val slots_mem_lsuOp   = RegInit(VecInit(Seq.fill(depth)(LsuOp.LB)))
  val slots_might_flush = RegInit(VecInit(Seq.fill(depth)(false.B)))

  /** Slot i is valid iff it lies in [head_phys, head_phys+count) (circular). */
  def slotValid(idx: UInt): Bool = (idx - head_phys)(idWidth - 1, 0) < count

  /** Tree mux: O(log depth) for indexed read. */
  private def muxTree[T <: Data](idx: UInt, data: Seq[T]): T = {
    if (data.size == 1) data.head
    else {
      val half = data.size / 2
      val level = chisel3.util.log2Ceil(data.size) - 1
      val sel = idx(level)
      val left = muxTree(idx, data.take(half))
      val right = muxTree(idx, data.drop(half))
      Mux(sel, right, left)
    }
  }

  def readSlot(idx: UInt): RobSlotRead = {
    val s = Wire(new RobSlotRead)
    s.valid       := slotValid(idx)
    s.is_done     := muxTree(idx, slots_is_done)
    s.need_mem    := muxTree(idx, slots_need_mem)
    s.rd_value    := muxTree(idx, slots_rd_value)
    s.mem_lsuOp   := muxTree(idx, slots_mem_lsuOp)
    s.might_flush := muxTree(idx, slots_might_flush)
    s
  }

  val head_is_done   = muxTree(head_phys, slots_is_done)
  val head_need_mem  = muxTree(head_phys, slots_need_mem)
  val head_next_pc   = muxTree(head_phys, slots_next_pc)
  val head_rd_index  = muxTree(head_phys, slots_rd_index)
  val head_rd_value  = muxTree(head_phys, slots_rd_value)
  val head_flush     = muxTree(head_phys, slots_flush)

  // -------- Enq --------

  val empty = head_ptr === tail_ptr
  val full  = (head_phys === tail_phys) && (head_ptr(ptrWidth - 1) =/= tail_ptr(ptrWidth - 1))
  enq.rob_id := tail_phys
  enq.req.ready := !full

  // -------- Commit --------

  val head_done = !empty && head_is_done
  val do_flush  = io.commit.valid && head_flush

  io.commit.valid := head_done
  io.commit.bits.next_pc   := head_next_pc
  io.commit.bits.rd_index  := head_rd_index
  io.commit.bits.rd_value  := head_rd_value
  io.commit.bits.mem_count := Mux(head_need_mem, 1.U(32.W), 0.U(32.W))

  io.rat_rob_write.valid := io.commit.valid
  io.rat_rob_write.bits.rd_index := head_rd_index
  io.rat_rob_write.bits.rob_id   := head_phys

  io.accessPorts.zipWithIndex.foreach { case (p, i) =>
    p.ready := Mux((i == aguPortIndex).B, mem.ls_enq_ready, true.B)
    p.flush := do_flush
  }

  io.slotReadRs1.slot := readSlot(io.slotReadRs1.rob_id)
  io.slotReadRs2.slot := readSlot(io.slotReadRs2.rob_id)

  // -------- Mem issue to MemUnit; safe_ptr for ordering --------

  val safe_ptr = RegInit(0.U(idWidth.W))
  val safe_slot = readSlot(safe_ptr)
  val safe_offset = (safe_ptr - head_phys)(idWidth - 1, 0)
  val safe_ptr_before_tail = safe_offset < count

  val ls_head_rob_id = mem.issue_rob_id.bits
  val ls_head_offset = (ls_head_rob_id - head_phys)(idWidth - 1, 0)
  val ls_head_in_safe = mem.issue_rob_id.valid && slotValid(ls_head_rob_id) && (ls_head_offset < safe_offset)

  mem.issue := !do_flush && ls_head_in_safe
  mem.flush := do_flush
  mem.resp.ready := !do_flush

  // -------- Main Update Logic --------

  when(do_flush) {
    head_ptr := 0.U
    tail_ptr := 0.U
    safe_ptr := 0.U
    for (i <- 0 until depth) {
      slots_rd_index(i)    := 0.U
      slots_is_done(i)     := false.B
      slots_need_mem(i)    := false.B
      slots_rd_value(i)    := 0.U
      slots_next_pc(i)     := 0.U
      slots_flush(i)       := false.B
      slots_might_flush(i) := false.B
      slots_mem_lsuOp(i)   := LsuOp.LB
    }
  }.otherwise {
    // safe_ptr: sync to head when stale; else advance when !might_flush and before tail
    when(safe_offset >= count) {
      safe_ptr := head_phys
    }.elsewhen(safe_slot.valid && !safe_slot.might_flush && safe_ptr_before_tail) {
      safe_ptr := (safe_ptr + 1.U)(idWidth - 1, 0)
    }

    applyFuUpdates()
    applyMemSlotUpdates()
    applyEnq()
    applyCommit()
  }

  // -------- Slot updates by write source --------
  // IS (enq): rd_index, might_flush, flush(init). is_done/need_mem init false.
  def applyEnq(): Unit = {
    when(enq.req.fire) {
      val idx = tail_phys
      slots_rd_index(idx)    := enq.req.bits.rd_index
      slots_might_flush(idx) := enq.req.bits.might_flush
      slots_flush(idx)       := false.B  // clear on slot reuse
      tail_ptr := (tail_ptr + 1.U)(ptrWidth - 1, 0)
    }
  }

  // FU: is_done, need_mem, rd_value, next_pc, flush, mem_lsuOp (AGU). Mem addr/wdata/wstrb in LS_Queue.
  def applyFuUpdates(): Unit = {
    for (p <- io.accessPorts) {
      when(p.valid) {
        val idx = p.bits.rob_id
        slots_is_done(idx) := p.bits.is_done
        slots_need_mem(idx) := p.bits.need_mem
        slots_next_pc(idx) := p.bits.next_pc
        when(p.bits.is_done) {
          slots_rd_value(idx) := p.bits.rd_value
        }
        when(p.bits.need_mem) {
          slots_mem_lsuOp(idx) := p.bits.mem_lsuOp
        }
        when(p.bits.flush) {
          slots_flush(idx) := true.B
        }.elsewhen(p.bits.is_done) {
          // Branch resolved as not taken: clear might_flush so safe_ptr can advance
          slots_might_flush(idx) := false.B
        }
      }
    }
  }

  // MemUnit: resp→is_done; rd_value (resp for load only). req needs no slot update.
  def applyMemSlotUpdates(): Unit = {
    when(mem.resp.fire) {
      val idx = mem.resp.bits.rob_id
      slots_is_done(idx) := true.B
      val isLoad = slots_mem_lsuOp(idx) =/= LsuOp.SB &&
                   slots_mem_lsuOp(idx) =/= LsuOp.SH &&
                   slots_mem_lsuOp(idx) =/= LsuOp.SW
      when(isLoad) {
        slots_rd_value(idx) := mem.resp.bits.data
      }
    }
  }

  def applyCommit(): Unit = {
    when(io.commit.valid) {
      slots_is_done(head_ptr(idWidth - 1, 0)) := false.B
      head_ptr := (head_ptr + 1.U)(ptrWidth - 1, 0)
    }
  }
}
