package nzea_core.retire.rob

import chisel3._
import chisel3.util.{Decoupled, Valid}
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
  *
  * Slot fields and write sources (同一 slot 每周期仅被一个来源写入):
  * | Field        | IS (enq)     | FU              | MemUnit        |
  * |--------------|--------------|-----------------|----------------|
  * | rd_index     | ✓            |                 |                |
  * | might_flush  | ✓            |                 |                |
  * | rob_state    | ✓ Executing  | ✓ Done/WaitMem  | ✓ WaitResult/Done |
  * | next_pc      | ✓ pred_next  | ✓ (when flush)  |                |
  * | flush        | ✓ false(init)| ✓ (when branch) |               |
  * | rd_value     |              | ✓ Done/WaitMem  | ✓ (load resp)  |
  * | mem_wdata    |              | ✓ (AGU only)    |                |
  * | mem_wstrb    |              | ✓ (AGU only)    |                |
  * | mem_lsuOp    |              | ✓ (AGU only)    |                |
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

  // -------- Pointers & Split Slot Regs --------

  val head_ptr = RegInit(0.U(idWidth.W))
  val tail_ptr = RegInit(0.U(idWidth.W))
  val count    = RegInit(0.U((idWidth + 1).W))

  // Each field in its own Reg; writers only touch what they need
  val slots_rd_index  = RegInit(VecInit(Seq.fill(depth)(0.U(5.W))))
  val slots_rob_state = RegInit(VecInit(Seq.fill(depth)(RobState.Executing)))
  val slots_rd_value  = RegInit(VecInit(Seq.fill(depth)(0.U(32.W))))
  val slots_next_pc   = RegInit(VecInit(Seq.fill(depth)(0.U(32.W))))
  val slots_flush     = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val slots_mem_wdata   = RegInit(VecInit(Seq.fill(depth)(0.U(32.W))))
  val slots_mem_wstrb   = RegInit(VecInit(Seq.fill(depth)(0.U(4.W))))
  val slots_mem_lsuOp   = RegInit(VecInit(Seq.fill(depth)(LsuOp.LB)))
  val slots_might_flush = RegInit(VecInit(Seq.fill(depth)(false.B)))

  /** Slot i is valid iff it lies in [head_ptr, head_ptr+count) (circular). */
  def slotValid(idx: UInt): Bool = (idx - head_ptr)(idWidth - 1, 0) < count

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
    s.rob_state   := muxTree(idx, slots_rob_state)
    s.rd_value    := muxTree(idx, slots_rd_value)
    s.mem_wdata   := muxTree(idx, slots_mem_wdata)
    s.mem_wstrb   := muxTree(idx, slots_mem_wstrb)
    s.mem_lsuOp   := muxTree(idx, slots_mem_lsuOp)
    s.might_flush := muxTree(idx, slots_might_flush)
    s
  }

  val head_rob_state = muxTree(head_ptr, slots_rob_state)
  val head_next_pc  = muxTree(head_ptr, slots_next_pc)
  val head_rd_index = muxTree(head_ptr, slots_rd_index)
  val head_rd_value = muxTree(head_ptr, slots_rd_value)
  val head_flush    = muxTree(head_ptr, slots_flush)

  // -------- Enq --------

  val full = count === depth.U
  enq.rob_id := tail_ptr
  enq.req.ready := !full

  // -------- Commit --------

  val head_done = (count > 0.U) && head_rob_state === RobState.Done
  val do_flush  = io.commit.valid && head_flush

  io.commit.valid := head_done
  io.commit.bits.next_pc  := head_next_pc
  io.commit.bits.rd_index := head_rd_index
  io.commit.bits.rd_value := head_rd_value

  io.accessPorts.foreach { p =>
    p.ready := true.B
    p.flush := do_flush
  }

  io.slotReadRs1.slot := readSlot(io.slotReadRs1.rob_id)
  io.slotReadRs2.slot := readSlot(io.slotReadRs2.rob_id)

  io.rat_rob_write.valid := io.commit.valid
  io.rat_rob_write.bits.rd_index := head_rd_index
  io.rat_rob_write.bits.rob_id   := head_ptr

  // -------- Submodules --------

  val memReqManager = Module(new MemReqManager(depth, idWidth))
  memReqManager.io.mem <> mem
  memReqManager.io.flush := do_flush
  memReqManager.io.headPtr := head_ptr
  memReqManager.io.tailPtr := tail_ptr
  memReqManager.io.count := count
  memReqManager.io.slotReqRead.slot := readSlot(memReqManager.io.slotReqRead.rob_id)
  memReqManager.io.slotRespRead.slot := readSlot(memReqManager.io.slotRespRead.rob_id)
  memReqManager.io.slotSafeRead.slot := readSlot(memReqManager.io.slotSafeRead.rob_id)

  // -------- Main Update Logic --------

  when(do_flush) {
    head_ptr := 0.U
    tail_ptr := 0.U
    count := 0.U
    for (i <- 0 until depth) {
      slots_rd_index(i)    := 0.U
      slots_rob_state(i)   := RobState.Executing
      slots_rd_value(i)    := 0.U
      slots_next_pc(i)     := 0.U
      slots_flush(i)       := false.B
      slots_might_flush(i) := false.B
      slots_mem_wdata(i)   := 0.U
      slots_mem_wstrb(i)   := 0.U
      slots_mem_lsuOp(i)   := LsuOp.LB
    }
  }.otherwise {
    applyFuUpdates()
    applyMemSlotUpdates()
    applyEnq()
    applyCommit()
    updateCount()
  }

  // -------- Slot updates by write source --------
  // IS (enq): rd_index, might_flush, rob_state, next_pc, flush(init). No rd_value/mem_*.
  def applyEnq(): Unit = {
    when(enq.req.fire) {
      val idx = tail_ptr
      slots_rd_index(idx)    := enq.req.bits.rd_index
      slots_might_flush(idx) := enq.req.bits.might_flush
      slots_rob_state(idx)   := RobState.Executing
      slots_next_pc(idx)     := enq.req.bits.pred_next_pc
      slots_flush(idx)       := false.B  // clear on slot reuse
      tail_ptr := (tail_ptr + 1.U)(idWidth - 1, 0)
    }
  }

  // FU: rd_value, next_pc, flush, rob_state, mem_wdata/wstrb/lsuOp (AGU only).
  def applyFuUpdates(): Unit = {
    for (p <- io.accessPorts) {
      when(p.valid) {
        val idx = p.bits.rob_id
        slots_rob_state(idx) := p.bits.new_state
        when(p.bits.new_state === RobState.Done) {
          slots_rd_value(idx) := p.bits.rd_value
        }
        when(p.bits.new_state === RobState.WaitingForMem) {
          slots_rd_value(idx)   := p.bits.rd_value
          slots_mem_wdata(idx) := p.bits.mem_wdata
          slots_mem_wstrb(idx) := p.bits.mem_wstrb
          slots_mem_lsuOp(idx) := p.bits.mem_lsuOp
        }
        when(p.bits.flush) {
          slots_flush(idx)   := true.B
          slots_next_pc(idx) := p.bits.next_pc
        }
      }
    }
  }

  // MemUnit: rob_state (req→WaitingForResult, resp→Done); rd_value (resp for load only).
  def applyMemSlotUpdates(): Unit = {
    when(mem.req.fire) {
      slots_rob_state(mem.req.bits.rob_id) := RobState.WaitingForResult
    }
    when(mem.resp.fire) {
      val idx = mem.resp.bits.rob_id
      slots_rob_state(idx) := RobState.Done
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
      head_ptr := (head_ptr + 1.U)(idWidth - 1, 0)
    }
  }

  def updateCount(): Unit = {
    count := count + Mux(enq.req.fire, 1.U, 0.U) - Mux(io.commit.valid, 1.U, 0.U)
  }
}
