package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}
import nzea_core.backend.fu.LsuOp

/** ROB entry state: Executing -> (ALU/BRU/SYSU: Done; AGU: WaitingForMem ->
  * WaitingForResult when mem_req.fire -> Done when mem_resp.fire).
  */
object RobState extends chisel3.ChiselEnum {
  val Executing = Value // just entered ROB
  val WaitingForMem =
    Value // AGU done, req not sent yet, eligible for mem_req selection
  val WaitingForResult = Value // req sent, waiting for mem_resp
  val Done = Value // FU complete, ready to commit
}

/** One entry in the Rob. rd_value reused for mem_addr before load/store completes. */
class RobEntry extends Bundle {
  val rd_index   = UInt(5.W)
  val rob_state  = RobState()
  val rd_value   = UInt(32.W)  // load result, or mem_addr before mem completes
  val next_pc    = UInt(32.W)
  val flush      = Bool()
  val mem_wdata  = UInt(32.W)
  val mem_wstrb  = UInt(4.W)
  val mem_lsuOp  = LsuOp()
}

/** Payload for Rob enq: rd_index, pred_next_pc (stored in next_pc_vec at enq).
  */
class RobEnqPayload extends Bundle {
  val rd_index = UInt(5.W)
  val pred_next_pc = UInt(32.W)
}

/** Head output: rob_id, rob_state, rd_index, rd_value, next_pc (BRU writes;
  * else pred_next_pc from enq), flush.
  */
class RobHead(depth: Int) extends Bundle {
  val rob_id = UInt(chisel3.util.log2Ceil(depth.max(2)).W)
  val rob_state = RobState()
  val rd_index = UInt(5.W)
  val rd_value = UInt(32.W)
  val next_pc = UInt(32.W)
  val flush = Bool()
}

/** Commit info for WBU: only when head is Done. next_pc, rd_index, rd_value,
  * flush.
  */
class RobCommitInfo extends Bundle {
  val next_pc = UInt(32.W)
  val rd_index = UInt(5.W)
  val rd_value = UInt(32.W)
  val flush = Bool()
}

/** MemUnit request from Rob: rob_id, addr, wdata, wstrb, lsuOp. */
class RobMemReq(idWidth: Int) extends Bundle {
  val rob_id = UInt(idWidth.W)
  val addr   = UInt(32.W)
  val wdata  = UInt(32.W)
  val wstrb  = UInt(4.W)
  val lsuOp  = LsuOp()
}

/** MemUnit response to Rob: rob_id, data (load result; store ignores). */
class RobMemResp(idWidth: Int) extends Bundle {
  val rob_id = UInt(idWidth.W)
  val data = UInt(32.W)
}

/** GPR bypass payload: rd and data for ISU to bypass when oldest Done entry
  * commits.
  */
class GprBypass extends Bundle {
  val rd = UInt(5.W)
  val data = UInt(32.W)
}

/** ROB entry state update: rob_id, new_state, rd_value. For BRU: flush, next_pc.
  * For AGU WaitingForMem: rd_value=addr, mem_wdata, mem_wstrb, mem_lsuOp.
  */
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

/** FU output to Rob: valid/bits from FU, ready/flush from Rob. Compatible with
  * PipeIO for <> with FU.
  */
class RobAccessIO(idWidth: Int) extends Bundle {
  val valid = Output(Bool())
  val bits = Output(new RobEntryStateUpdate(idWidth))
  val ready = Input(Bool())
  val flush = Input(Bool())
}

/** Standard FU output: PipeIO or RobAccessIO. FUs output PipeIO; Rob connects
  * with ready=1, flush forwarded.
  */
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

/** Rob: depth-entry circular buffer. rob_id = stable buffer address. Call
  * connectFuOutputs(fuOutputs) to wire FU outputs; uses
  * Valid[RobEntryStateUpdate] as standard.
  */
class Rob(depth: Int, numAccessPorts: Int) extends Module {
  require(depth >= 1, "Rob depth must >= 1")
  require(numAccessPorts >= 1, "Rob numAccessPorts must >= 1")
  private val idWidth = chisel3.util.log2Ceil(depth.max(2))

  /** Wire FU outputs to access ports. Rob drives ready=1, flush when
    * commit.fire && head.flush.
    */
  def connectFuOutputs(fuOutputs: Seq[Rob.FuOutput]): Unit = {
    require(fuOutputs.size == numAccessPorts)
    (io.accessPorts zip fuOutputs).foreach { case (p, fu) =>
      p.valid := fu.valid
      p.bits := fu.bits
      fu.ready := p.ready
      fu.flush := p.flush
    }
  }

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new RobEnqPayload))
    val enq_rob_id = Output(UInt(idWidth.W))
    val commit = Decoupled(new RobCommitInfo)
    val mem_req = Decoupled(new RobMemReq(idWidth))
    val mem_resp = Flipped(Decoupled(new RobMemResp(idWidth)))
    val pending_rd = Output(UInt(32.W))
    val gpr_bypass = Output(Valid(new GprBypass))
    val accessPorts = Vec(numAccessPorts, Flipped(new RobAccessIO(idWidth)))
  })

  val head_ptr = RegInit(0.U(idWidth.W))
  val tail_ptr = RegInit(0.U(idWidth.W))
  val count = RegInit(0.U((idWidth + 1).W))
  val full = count === depth.U
  val empty = count === 0.U
  io.enq_rob_id := tail_ptr
  io.enq.ready := !full

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

  val do_flush = io.commit.fire && head_bits.flush
  io.accessPorts.foreach { p =>
    p.ready := true.B
    p.flush := do_flush
  }

  val head_done = head_slot.valid && head_bits.rob_state === RobState.Done
  io.commit.valid := head_done
  io.commit.bits.next_pc := head_bits.next_pc
  io.commit.bits.rd_index := head_bits.rd_index
  io.commit.bits.rd_value := head_bits.rd_value
  io.commit.bits.flush := head_bits.flush

  io.gpr_bypass.valid := head_slot.valid && head_bits.rob_state === RobState.Done
  io.gpr_bypass.bits.rd := head_bits.rd_index
  io.gpr_bypass.bits.data := head_bits.rd_value

  when(do_flush) {
    head_ptr := 0.U
    tail_ptr := 0.U
    count := 0.U
    for (i <- 0 until depth) {
      slots(i).valid := false.B
      slots(i).bits := emptyEntry
    }
  }.otherwise {
    for (p <- io.accessPorts) {
      when(p.valid) {
        slots(p.bits.rob_id).bits.rob_state := p.bits.new_state
        when(p.bits.new_state === RobState.Done) {
          slots(p.bits.rob_id).bits.rd_value := p.bits.rd_value
        }
        when(p.bits.flush) {
          slots(p.bits.rob_id).bits.flush := true.B
          slots(p.bits.rob_id).bits.next_pc := p.bits.next_pc
        }
        when(p.bits.new_state === RobState.WaitingForMem) {
          slots(p.bits.rob_id).bits.rd_value := p.bits.rd_value
          slots(p.bits.rob_id).bits.mem_wdata := p.bits.mem_wdata
          slots(p.bits.rob_id).bits.mem_wstrb := p.bits.mem_wstrb
          slots(p.bits.rob_id).bits.mem_lsuOp := p.bits.mem_lsuOp
        }
      }
    }
    when(io.enq.fire) {
      slots(tail_ptr).valid := true.B
      slots(tail_ptr).bits.rd_index := io.enq.bits.rd_index
      slots(tail_ptr).bits.rob_state := RobState.Executing
      slots(tail_ptr).bits.rd_value := 0.U
      slots(tail_ptr).bits.next_pc := io.enq.bits.pred_next_pc
      slots(tail_ptr).bits.flush := false.B
      tail_ptr := (tail_ptr + 1.U)(idWidth - 1, 0)
    }
    when(io.commit.fire) {
      slots(head_ptr).valid := false.B
      head_ptr := (head_ptr + 1.U)(idWidth - 1, 0)
    }
    count := count + Mux(io.enq.fire, 1.U, 0.U) - Mux(io.commit.fire, 1.U, 0.U)
  }

  val waiting_for_mem = (0 until depth).map { j =>
    val idx = (head_ptr + j.U)(idWidth - 1, 0)
    val is_waiting_mem =
      slots(idx).valid && slots(idx).bits.rob_state === RobState.WaitingForMem
    val prefix_all_waiting_result = (0 until j)
      .map { k =>
        val kidx = (head_ptr + k.U)(idWidth - 1, 0)
        slots(kidx).valid && slots(
          kidx
        ).bits.rob_state === RobState.WaitingForResult
      }
      .foldLeft(true.B)(_ && _)
    is_waiting_mem && prefix_all_waiting_result
  }
  val sel_j = PriorityEncoder(waiting_for_mem)
  val sel_idx = (head_ptr + sel_j)(idWidth - 1, 0)
  val sel_slot = slots(sel_idx)
  val has_waiting = waiting_for_mem.reduce(_ || _)
  io.mem_req.valid := !do_flush && has_waiting
  io.mem_req.bits.rob_id := sel_idx
  io.mem_req.bits.addr := sel_slot.bits.rd_value
  io.mem_req.bits.wdata := sel_slot.bits.mem_wdata
  io.mem_req.bits.wstrb := sel_slot.bits.mem_wstrb
  io.mem_req.bits.lsuOp := sel_slot.bits.mem_lsuOp
  io.mem_resp.ready := true.B
  when(!do_flush && io.mem_req.fire) {
    slots(sel_idx).bits.rob_state := RobState.WaitingForResult
  }
  when(!do_flush && io.mem_resp.fire) {
    slots(io.mem_resp.bits.rob_id).bits.rob_state := RobState.Done
    val resp_slot = slots(io.mem_resp.bits.rob_id)
    val is_load = resp_slot.bits.mem_lsuOp =/= LsuOp.SB && resp_slot.bits.mem_lsuOp =/= LsuOp.SH && resp_slot.bits.mem_lsuOp =/= LsuOp.SW
    when(is_load) { resp_slot.bits.rd_value := io.mem_resp.bits.data }
  }

  val pending_rd = RegInit(0.U(32.W))
  val other_same_rd = (0 until depth)
    .map { i =>
      slots(i).valid && slots(
        i
      ).bits.rd_index === head_bits.rd_index && head_ptr =/= i.U
    }
    .reduce(
      _ || _
    ) || (io.enq.fire && io.enq.bits.rd_index === head_bits.rd_index)
  val to_remove = Mux(
    io.commit.fire && !other_same_rd,
    (1.U(32.W) << head_bits.rd_index),
    0.U(32.W)
  )
  val to_add = Mux(io.enq.fire, (1.U(32.W) << io.enq.bits.rd_index), 0.U(32.W))
  val flush_r = RegNext(do_flush)
  pending_rd := Mux(flush_r, 0.U(32.W), (pending_rd & ~to_remove) | to_add)
  io.pending_rd := pending_rd
}
