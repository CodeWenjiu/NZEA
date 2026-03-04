package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, PriorityEncoder, Valid}
import nzea_core.backend.fu.LsuOp

// -------- RobState & Bundles --------

/** ROB entry state: Executing -> (ALU/BRU/SYSU: Done; AGU: WaitingForMem ->
  * WaitingForResult when mem_req.fire -> Done when mem_resp.fire).
  */
object RobState extends chisel3.ChiselEnum {
  val Executing = Value
  val WaitingForMem = Value
  val WaitingForResult = Value
  val Done = Value
}

/** One entry in the Rob. rd_value reused for mem_addr before load/store completes. */
class RobEntry extends Bundle {
  val rd_index  = UInt(5.W)
  val rob_state = RobState()
  val rd_value  = UInt(32.W)
  val next_pc   = UInt(32.W)
  val flush     = Bool()
  val mem_wdata = UInt(32.W)
  val mem_wstrb = UInt(4.W)
  val mem_lsuOp = LsuOp()
}

/** Payload for Rob enq: rd_index, pred_next_pc (stored in next_pc at enq). */
class RobEnqPayload extends Bundle {
  val rd_index     = UInt(5.W)
  val pred_next_pc = UInt(32.W)
}

/** Commit info for WBU: next_pc, rd_index, rd_value, flush (only when head is Done). */
class RobCommitInfo extends Bundle {
  val next_pc   = UInt(32.W)
  val rd_index  = UInt(5.W)
  val rd_value  = UInt(32.W)
  val flush     = Bool()
}

/** MemUnit request: rob_id, addr, wdata, wstrb, lsuOp. */
class RobMemReq(idWidth: Int) extends Bundle {
  val rob_id = UInt(idWidth.W)
  val addr   = UInt(32.W)
  val wdata  = UInt(32.W)
  val wstrb  = UInt(4.W)
  val lsuOp  = LsuOp()
}

/** MemUnit response: rob_id, data (load result; store ignores). */
class RobMemResp(idWidth: Int) extends Bundle {
  val rob_id = UInt(idWidth.W)
  val data   = UInt(32.W)
}

/** RAT request: rs1/rs2 indices and GPR values (fallback when no bypass). */
class RatReq extends Bundle {
  val rs1_index = UInt(5.W)
  val rs2_index = UInt(5.W)
  val rs1_data  = UInt(32.W)
  val rs2_data  = UInt(32.W)
}

/** RAT response: is_stall (OR of rs1/rs2 stall), rs1_val/rs2_val (bypass or fallback). */
class RatResp extends Bundle {
  val is_stall = Bool()
  val rs1_val  = UInt(32.W)
  val rs2_val  = UInt(32.W)
}

/** FU output to Rob: state update for an entry. */
class RobEntryStateUpdate(idWidth: Int) extends Bundle {
  val rob_id    = UInt(idWidth.W)
  val new_state = RobState()
  val rd_value  = UInt(32.W)
  val flush     = Bool()
  val next_pc   = UInt(32.W)
  val mem_wdata = UInt(32.W)
  val mem_wstrb = UInt(4.W)
  val mem_lsuOp = LsuOp()
}

/** FU output to Rob: valid/bits from FU, ready/flush from Rob. */
class RobAccessIO(idWidth: Int) extends Bundle {
  val valid = Output(Bool())
  val bits  = Output(new RobEntryStateUpdate(idWidth))
  val ready = Input(Bool())
  val flush = Input(Bool())
}

object Rob {
  type FuOutput = RobAccessIO

  def entryStateUpdate(
      valid: Bool,
      rob_id: UInt,
      new_state: RobState.Type,
      rd_value: UInt,
      flush: Bool = false.B,
      next_pc: UInt = 0.U,
      mem_wdata: UInt = 0.U,
      mem_wstrb: UInt = 0.U,
      mem_lsuOp: LsuOp.Type = LsuOp.LB
  )(idWidth: Int): Valid[RobEntryStateUpdate] = {
    val w = Wire(Valid(new RobEntryStateUpdate(idWidth)))
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

// -------- Rob Module --------

/** Rob: depth-entry circular buffer. rob_id = stable slot index.
  * Call connectFuOutputs(fuOutputs) to wire FU outputs.
  */
class Rob(depth: Int, numAccessPorts: Int) extends Module {
  require(depth >= 1, "Rob depth must >= 1")
  require(numAccessPorts >= 1, "Rob numAccessPorts must >= 1")

  private val idWidth = chisel3.util.log2Ceil(depth.max(2))

  val enq = IO(new Bundle {
    val req    = Flipped(Decoupled(new RobEnqPayload))
    val rob_id = Output(UInt(idWidth.W))
  })
  val mem = IO(new Bundle {
    val req  = Decoupled(new RobMemReq(idWidth))
    val resp = Flipped(Decoupled(new RobMemResp(idWidth)))
  })
  val rat = IO(new Bundle {
    val req  = Input(new RatReq)
    val resp = Output(new RatResp)
  })
  val io  = IO(new Bundle {
    val commit       = Decoupled(new RobCommitInfo)
    val accessPorts  = Vec(numAccessPorts, Flipped(new RobAccessIO(idWidth)))
  })

  def connectFuOutputs(fuOutputs: Seq[Rob.FuOutput]): Unit = {
    require(fuOutputs.size == numAccessPorts)
    (io.accessPorts zip fuOutputs).foreach { case (p, fu) =>
      p.valid := fu.valid
      p.bits := fu.bits
      fu.ready := p.ready
      fu.flush := p.flush
    }
  }

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
  val do_flush  = io.commit.fire && head_bits.flush

  io.commit.valid := head_done
  io.commit.bits.next_pc  := head_bits.next_pc
  io.commit.bits.rd_index := head_bits.rd_index
  io.commit.bits.rd_value := head_bits.rd_value
  io.commit.bits.flush    := head_bits.flush

  io.accessPorts.foreach { p =>
    p.ready := true.B
    p.flush := do_flush
  }

  // -------- Slot Updates --------

  // -------- RAT (Register Alias Table) --------
  // ratTable(rd) = rob_id of oldest in-flight writer; stallTable(rd) = waiting for result (split for timing)
  class RatEntry extends Bundle {
    val valid  = Bool()
    val rob_id = UInt(idWidth.W)
  }
  val ratTable = RegInit(VecInit(Seq.fill(32)({
    val e = Wire(new RatEntry)
    e.valid := false.B
    e.rob_id := 0.U
    e
  })))
  val stallTable = RegInit(VecInit(Seq.fill(32)(false.B)))

  when(do_flush) {
    head_ptr := 0.U
    tail_ptr := 0.U
    count := 0.U
    for (i <- 0 until depth) {
      slots(i).valid := false.B
      slots(i).bits := emptyEntry
    }
    for (i <- 0 until 32) {
      ratTable(i).valid := false.B
      stallTable(i) := false.B
    }
  }.otherwise {
    applyFuUpdates()
    stallTableClearOnComplete()
    ratUpdateOnCommit()
    ratUpdateOnEnq()
    applyEnq()
    applyCommit()
    updateCount()
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
    when(io.commit.fire) {
      slots(head_ptr).valid := false.B
      head_ptr := (head_ptr + 1.U)(idWidth - 1, 0)
    }
  }

  def updateCount(): Unit = {
    count := count + Mux(enq.req.fire, 1.U, 0.U) - Mux(io.commit.fire, 1.U, 0.U)
  }

  def stallTableClearOnComplete(): Unit = {
    val completedFromFu = io.accessPorts.map { p => (p.valid && p.bits.new_state === RobState.Done, p.bits.rob_id) }
    val completedFromMem = (mem.resp.fire, mem.resp.bits.rob_id)
    val allCompleted = completedFromFu :+ completedFromMem
    for (i <- 0 until 32) {
      when(ratTable(i).valid) {
        val matches = allCompleted.map { case (v, rid) => v && ratTable(i).rob_id === rid }.reduce(_ || _)
        when(matches) {
          stallTable(i) := false.B
        }
      }
    }
  }

  def ratUpdateOnCommit(): Unit = {
    when(io.commit.fire) {
      val rd = head_bits.rd_index
      val nextWriterCandidates = (1 until depth).map { offset =>
        val idx = idxFromHead(offset.U)
        val inRange = offset.U < count
        slots(idx).valid && inRange && slots(idx).bits.rd_index === rd
      }
      val hasNextInSlots = nextWriterCandidates.reduce(_ || _)
      val nextOffset = Mux(hasNextInSlots, PriorityEncoder(VecInit(nextWriterCandidates).asUInt) + 1.U, 0.U)
      val nextRobId = idxFromHead(nextOffset)
      when(rd =/= 0.U) {
        when(hasNextInSlots) {
          ratTable(rd).valid := true.B
          ratTable(rd).rob_id := nextRobId
          stallTable(rd) := true.B
        }.elsewhen(enq.req.fire && enq.req.bits.rd_index === rd) {
          ratTable(rd).valid := true.B
          ratTable(rd).rob_id := tail_ptr
          stallTable(rd) := true.B
        }.otherwise {
          ratTable(rd).valid := false.B
          stallTable(rd) := false.B
        }
      }
    }
  }

  def ratUpdateOnEnq(): Unit = {
    when(enq.req.fire) {
      val rd = enq.req.bits.rd_index
      when(rd =/= 0.U && !ratTable(rd).valid) {
        ratTable(rd).valid := true.B
        ratTable(rd).rob_id := tail_ptr
        stallTable(rd) := true.B
      }
    }
  }

  // -------- RAT Lookup (stall & bypass) --------
  // Stall: use RAT(rs).stall directly (no slots lookup)
  // Bypass: still need slots(rob_id).rd_value when rob_state === Done

  def ratLookup(req: RatReq): RatResp = {
    val resp = Wire(new RatResp)
    def lookup(rsIndex: UInt, rsData: UInt): (Bool, UInt) = {
      val needStall = rsIndex =/= 0.U && ratTable(rsIndex).valid && stallTable(rsIndex)
      val bypassVal = Mux(
        rsIndex === 0.U,
        0.U(32.W),
        Mux(
          ratTable(rsIndex).valid && !stallTable(rsIndex),
          slots(ratTable(rsIndex).rob_id).bits.rd_value,
          rsData
        )
      )
      (needStall, bypassVal)
    }
    val (stall1, val1) = lookup(req.rs1_index, req.rs1_data)
    val (stall2, val2) = lookup(req.rs2_index, req.rs2_data)
    resp.is_stall := stall1 || stall2
    resp.rs1_val := val1
    resp.rs2_val := val2
    resp
  }

  rat.resp := ratLookup(rat.req)

  // -------- Mem Request Selection (two pointers) --------
  // mem_req_ptr: slot to issue mem_req (WaitingForMem)
  // mem_wait_ptr: slot waiting for mem_resp (WaitingForResult)

  def findNextWaitingForMem(ptr: UInt): (Bool, UInt) = {
    val candidates = (1 until depth).map { offset =>
      val idx = (ptr + offset.U)(idWidth - 1, 0)
      val allBetweenWaitingResult = (1 until offset)
        .map { k =>
          val kidx = (ptr + k.U)(idWidth - 1, 0)
          slots(kidx).valid && slots(kidx).bits.rob_state === RobState.WaitingForResult
        }
        .foldLeft(true.B)(_ && _)
      slots(idx).valid && slots(idx).bits.rob_state === RobState.WaitingForMem && allBetweenWaitingResult
    }
    val n = candidates.size
    val found = candidates.reduce(_ || _)
    val enc = PriorityEncoder(VecInit(candidates).asUInt)
    val values = (1 until depth).map(offset => (ptr + offset.U)(idWidth - 1, 0))
    val nextIdx = Mux1H((0 until n).map(i => enc === i.U), values)
    (found, nextIdx)
  }

  def findNextWaitingForResult(ptr: UInt): (Bool, UInt) = {
    val candidates = (1 until depth).map { offset =>
      val idx = (ptr + offset.U)(idWidth - 1, 0)
      slots(idx).valid && slots(idx).bits.rob_state === RobState.WaitingForResult
    }
    val n = candidates.size
    val found = candidates.reduce(_ || _)
    val enc = PriorityEncoder(VecInit(candidates).asUInt)
    val values = (1 until depth).map(offset => (ptr + offset.U)(idWidth - 1, 0))
    val nextIdx = Mux1H((0 until n).map(i => enc === i.U), values)
    (found, nextIdx)
  }

  def findFirstWaitingForMem(): (Bool, UInt) = {
    val candidates = (0 until depth).map { j =>
      val i = idxFromHead(j.U)
      val prefixAllWaitingResult = (0 until j)
        .map { k =>
          val kidx = idxFromHead(k.U)
          slots(kidx).valid && slots(kidx).bits.rob_state === RobState.WaitingForResult
        }
        .foldLeft(true.B)(_ && _)
      slots(i).valid && slots(i).bits.rob_state === RobState.WaitingForMem && prefixAllWaitingResult
    }
    val found = candidates.reduce(_ || _)
    val enc = PriorityEncoder(VecInit(candidates).asUInt)
    val values = (0 until depth).map(j => idxFromHead(j.U))
    val idx = Mux1H((0 until depth).map(i => enc === i.U), values)
    (found, idx)
  }

  def findFirstWaitingForResult(): (Bool, UInt) = {
    val candidates = (0 until depth).map { j =>
      val i = idxFromHead(j.U)
      slots(i).valid && slots(i).bits.rob_state === RobState.WaitingForResult
    }
    val found = candidates.reduce(_ || _)
    val enc = PriorityEncoder(VecInit(candidates).asUInt)
    val values = (0 until depth).map(j => idxFromHead(j.U))
    val idx = Mux1H((0 until depth).map(i => enc === i.U), values)
    (found, idx)
  }

  val mem_req_ptr  = RegInit(0.U(idWidth.W))
  val mem_req_valid = RegInit(false.B)
  val mem_wait_ptr  = RegInit(0.U(idWidth.W))
  val mem_wait_valid = RegInit(false.B)

  when(do_flush) {
    mem_req_valid := false.B
    mem_wait_valid := false.B
  }.otherwise {
    when(mem.req.fire) {
      slots(mem_req_ptr).bits.rob_state := RobState.WaitingForResult
      when(!mem_wait_valid) {
        mem_wait_ptr := mem_req_ptr
        mem_wait_valid := true.B
      }
      val (hasNext, nextPtr) = findNextWaitingForMem(mem_req_ptr)
      mem_req_ptr := nextPtr
      mem_req_valid := hasNext
    }.elsewhen(!mem_req_valid) {
      val (found, idx) = findFirstWaitingForMem()
      when(found) {
        mem_req_ptr := idx
        mem_req_valid := true.B
      }
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
      mem_wait_valid := false.B
      val (respHasNext, respNextPtr) = findNextWaitingForResult(mem.resp.bits.rob_id)
      when(respHasNext) {
        mem_wait_ptr := respNextPtr
        mem_wait_valid := true.B
      }
    }.elsewhen(!mem_wait_valid) {
      val (found, idx) = findFirstWaitingForResult()
      when(found) {
        mem_wait_ptr := idx
        mem_wait_valid := true.B
      }
    }
  }

  val mem_req_slot = slots(mem_req_ptr)
  mem.req.valid := !do_flush && mem_req_valid
  mem.req.bits.rob_id := mem_req_ptr
  mem.req.bits.addr   := mem_req_slot.bits.rd_value
  mem.req.bits.wdata  := mem_req_slot.bits.mem_wdata
  mem.req.bits.wstrb  := mem_req_slot.bits.mem_wstrb
  mem.req.bits.lsuOp  := mem_req_slot.bits.mem_lsuOp
  mem.resp.ready := true.B

}
