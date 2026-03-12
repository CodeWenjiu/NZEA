package nzea_core.retire.rob

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.MuxTree
import nzea_core.retire.CommitMsg
import nzea_core.retire.rob.RobMemType

// -------- Companion --------

/** Companion object: entryStateUpdate helper and apply for wiring. */
object Rob {
  /** Create Rob and wire fuOutputs; call from parent (e.g. Core) so connection context is correct. */
  def apply(depth: Int, fuOutputs: Seq[RobAccessIO], aguPortIndex: Int = 3, prfAddrWidth: Int = 6): Rob = {
    val r = Module(new Rob(depth, fuOutputs.size, aguPortIndex, prfAddrWidth))
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
    flush: Bool = false.B,
    next_pc: UInt = 0.U
  )(idWidth: Int): chisel3.util.Valid[RobEntryStateUpdate] = {
    val w = Wire(chisel3.util.Valid(new RobEntryStateUpdate(idWidth)))
    w.valid := valid
    w.bits.rob_id := rob_id
    w.bits.is_done := is_done
    w.bits.flush := flush
    w.bits.next_pc := next_pc
    w
  }
}

// -------- Rob Module --------

/** Rob: depth-entry circular buffer. rob_id = stable slot index.
  * - Enq: ISU dispatches, writes rd_index, might_flush.
  * - FU updates: ALU/BRU/SYSU write is_done; AGU writes next_pc. mem_type set at enq (ISU).
  * - Mem: MemUnit holds LsBuffer; Rob issues by rob_id. rd_value from PRF(p_rd) at commit.
  * - Commit: head done → output CommitMsg, advance head.
  * - Flush: on branch mispredict, clear all.
  */
class Rob(depth: Int, numAccessPorts: Int, aguPortIndex: Int = 3, prfAddrWidth: Int = 6) extends Module {
  require(depth >= 1, "Rob depth must >= 1")
  require(numAccessPorts >= 1, "Rob numAccessPorts must >= 1")
  require(aguPortIndex >= 0 && aguPortIndex < numAccessPorts, "aguPortIndex must be valid")

  private val idWidth  = chisel3.util.log2Ceil(depth.max(2))
  private val ptrWidth = idWidth + 1  // MSB = wrap bit for empty/full

  // -------- IO --------

  val enq = IO(new RobEnqIO(idWidth, prfAddrWidth))
  val mem = IO(new RobMemIO(idWidth))
  val io = IO(new Bundle {
    val commit        = Valid(new CommitMsg(prfAddrWidth))
    val accessPorts   = Vec(numAccessPorts, Flipped(new RobAccessIO(idWidth)))
    val slotReadRs1   = new RobSlotReadPort(idWidth)
    val slotReadRs2   = new RobSlotReadPort(idWidth)
    val do_flush      = Output(Bool())  // for IDU RMT/FreeList restore
  })

  // -------- Pointers --------

  val head_ptr  = RegInit(0.U(ptrWidth.W))
  val tail_ptr  = RegInit(0.U(ptrWidth.W))
  val head_phys = head_ptr(idWidth - 1, 0)
  val tail_phys = tail_ptr(idWidth - 1, 0)
  val count     = (tail_ptr - head_ptr)(ptrWidth - 1, 0)

  val empty = head_ptr === tail_ptr
  val full  = (head_phys === tail_phys) && (head_ptr(ptrWidth - 1) =/= tail_ptr(ptrWidth - 1))

  // -------- Slot State (one Reg per field for independent update) --------

  val slots_rd_index    = RegInit(VecInit(Seq.fill(depth)(0.U(5.W))))
  val slots_is_done     = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val slots_mem_type    = RegInit(VecInit(Seq.fill(depth)(RobMemType.None)))
  val slots_next_pc     = RegInit(VecInit(Seq.fill(depth)(0.U(32.W))))
  val slots_flush       = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val slots_might_flush = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val slots_p_rd       = Reg(Vec(depth, UInt(prfAddrWidth.W)))
  val slots_old_p_rd   = Reg(Vec(depth, UInt(prfAddrWidth.W)))

  // PRF write: FU writes directly to PRF on completion; ROB only tracks for commit ordering.

  // -------- Slot Read --------

  def slotValid(idx: UInt): Bool = (idx - head_phys)(idWidth - 1, 0) < count

  def readSlot(idx: UInt): RobSlotRead = {
    val s = Wire(new RobSlotRead)
    s.valid       := slotValid(idx)
    s.is_done     := MuxTree(idx, slots_is_done)
    s.mem_type    := MuxTree(idx, slots_mem_type)
    s.might_flush := MuxTree(idx, slots_might_flush)
    s
  }

  // -------- Head Fields (for commit) --------

  val head_is_done   = MuxTree(head_phys, slots_is_done)
  val head_mem_type  = MuxTree(head_phys, slots_mem_type)
  val head_need_mem  = RobMemType.needMem(head_mem_type)
  val head_is_load   = RobMemType.isLoad(head_mem_type)
  val head_next_pc   = MuxTree(head_phys, slots_next_pc)
  val head_rd_index  = MuxTree(head_phys, slots_rd_index)
  val head_p_rd      = MuxTree(head_phys, slots_p_rd)
  val head_old_p_rd = MuxTree(head_phys, slots_old_p_rd)
  val head_flush     = MuxTree(head_phys, slots_flush)

  // -------- Enq --------

  enq.rob_id := tail_phys
  enq.req.ready := !full

  // -------- Commit --------

  val head_done     = !empty && head_is_done
  val do_flush_raw  = io.commit.valid && head_flush
  val do_flush      = RegNext(do_flush_raw, false.B)  // delay 1 cycle to break ROB->IDU/IFU critical path

  io.commit.valid := head_done && !do_flush  // suppress commit when flush is active (next-cycle flush)
  io.commit.bits.next_pc   := head_next_pc
  io.commit.bits.rd_index  := head_rd_index
  io.commit.bits.rd_value  := 0.U  // commit reads from PRF(p_rd) via Commit module
  io.commit.bits.p_rd      := head_p_rd
  io.commit.bits.old_p_rd  := head_old_p_rd
  io.commit.bits.mem_count := Mux(head_need_mem, 1.U(32.W), 0.U(32.W))
  io.commit.bits.is_load   := head_is_load

  io.do_flush := do_flush

  // -------- FU Access --------

  io.accessPorts.zipWithIndex.foreach { case (p, i) =>
    p.ready := Mux((i == aguPortIndex).B, mem.ls_enq_ready, true.B)
    p.flush := do_flush
  }

  io.slotReadRs1.slot := readSlot(io.slotReadRs1.rob_id)
  io.slotReadRs2.slot := readSlot(io.slotReadRs2.rob_id)

  // -------- Mem Issue (safe_ptr: only issue loads before first might_flush) --------

  val safe_ptr = RegInit(0.U(idWidth.W))
  val safe_slot = readSlot(safe_ptr)
  val safe_offset       = (safe_ptr - head_phys)(idWidth - 1, 0)
  val safe_ptr_before_tail = safe_offset < count

  val ls_head_rob_id   = mem.issue_rob_id.bits
  val ls_head_offset   = (ls_head_rob_id - head_phys)(idWidth - 1, 0)
  val ls_head_in_safe  = mem.issue_rob_id.valid && slotValid(ls_head_rob_id) && (ls_head_offset < safe_offset)

  mem.issue := !do_flush && ls_head_in_safe
  mem.flush := do_flush
  mem.resp.ready := !do_flush

  // -------- Slot Updates --------

  when(do_flush) {
    head_ptr := 0.U
    tail_ptr := 0.U
    safe_ptr := 0.U
    for (i <- 0 until depth) {
      slots_rd_index(i)    := 0.U
      slots_is_done(i)     := false.B
      slots_mem_type(i)    := RobMemType.None
      slots_next_pc(i)     := 0.U
      slots_flush(i)       := false.B
      slots_might_flush(i) := false.B
      slots_p_rd(i)         := 0.U
      slots_old_p_rd(i)     := 0.U
    }
  }.otherwise {
    when(safe_offset >= count) {
      // No might_flush in [head, tail): allow all mem ops. Point past tail so safe_offset=count.
      safe_ptr := tail_phys
    }.elsewhen(safe_slot.valid && !safe_slot.might_flush && safe_ptr_before_tail) {
      safe_ptr := (safe_ptr + 1.U)(idWidth - 1, 0)
    }
    slotUpdateEnq()
    slotUpdateFu()
    slotUpdateMem()
    slotUpdateCommit()
  }

  def slotUpdateEnq(): Unit = {
    when(enq.req.fire) {
      val idx = tail_phys
      slots_rd_index(idx)    := enq.req.bits.rd_index
      slots_might_flush(idx) := enq.req.bits.might_flush
      slots_mem_type(idx)    := enq.req.bits.mem_type
      slots_p_rd(idx)        := enq.req.bits.p_rd
      slots_old_p_rd(idx)    := enq.req.bits.old_p_rd
      slots_flush(idx)       := false.B
      tail_ptr := (tail_ptr + 1.U)(ptrWidth - 1, 0)
    }
  }

  def slotUpdateFu(): Unit = {
    for (p <- io.accessPorts) {
      when(p.valid) {
        val idx = p.bits.rob_id
        slots_is_done(idx) := p.bits.is_done
        slots_next_pc(idx) := p.bits.next_pc
        when(p.bits.flush) {
          slots_flush(idx) := true.B
        }.elsewhen(p.bits.is_done) {
          slots_might_flush(idx) := false.B
        }
      }
    }
  }

  def slotUpdateMem(): Unit = {
    when(mem.resp.fire) {
      val idx = mem.resp.bits.rob_id
      slots_is_done(idx) := true.B
    }
  }

  def slotUpdateCommit(): Unit = {
    when(io.commit.valid) {
      slots_is_done(head_ptr(idWidth - 1, 0)) := false.B
      head_ptr := (head_ptr + 1.U)(ptrWidth - 1, 0)
    }
  }
}

