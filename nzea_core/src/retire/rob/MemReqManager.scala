package nzea_core.retire.rob

import chisel3._

/** Memory Request Manager: req_ptr, resp_ptr, safe_ptr.
  *
  * safe_ptr: from head, chases tail; stops at first might_flush (branch/trap).
  *   Enables multiple consecutive mem requests when no flush in between.
  *
  * req_ptr: points to slot to consider for sending mem request.
  *   - In range: [head, tail), else don't advance.
  *   - Executing: don't advance.
  *   - WaitingForMem: send when req_ptr in [head, safe_ptr); on mem.req.fire, advance.
  *   - WaitingForResult/Done: advance (skip).
  *
  * resp_ptr: points to slot to consider for receiving mem response.
  *   - In range: [head, req_ptr], else don't advance.
  *   - Executing/WaitingForMem: wait.
  *   - WaitingForResult: on mem.resp.fire, advance.
  *   - Done: advance (skip).
  */
class MemReqManager(depth: Int, idWidth: Int) extends Module {
  val io = IO(new Bundle {
    val mem = new RobMemIO(idWidth)
    val slotReqRead  = Flipped(new RobSlotReadPort(idWidth))
    val slotRespRead = Flipped(new RobSlotReadPort(idWidth))
    val slotSafeRead = Flipped(new RobSlotReadPort(idWidth))
    val headPtr     = Input(UInt(idWidth.W))
    val tailPtr     = Input(UInt(idWidth.W))
    val count       = Input(UInt((idWidth + 1).W))
    val flush       = Input(Bool())
  })

  val req_ptr  = RegInit(0.U(idWidth.W))
  val resp_ptr = RegInit(0.U(idWidth.W))
  val safe_ptr = RegInit(0.U(idWidth.W))

  io.slotReqRead.rob_id  := req_ptr
  io.slotRespRead.rob_id := resp_ptr
  io.slotSafeRead.rob_id := safe_ptr

  val req_slot_valid  = io.slotReqRead.slot.valid
  val req_slot_state  = io.slotReqRead.slot.rob_state
  val resp_slot_valid = io.slotRespRead.slot.valid
  val resp_slot_state = io.slotRespRead.slot.rob_state
  val safe_slot_valid  = io.slotSafeRead.slot.valid
  val safe_slot_might_flush = io.slotSafeRead.slot.might_flush

  val req_offset  = (req_ptr - io.headPtr)(idWidth - 1, 0)
  val resp_offset = (resp_ptr - io.headPtr)(idWidth - 1, 0)
  val safe_offset = (safe_ptr - io.headPtr)(idWidth - 1, 0)

  val req_ptr_in_range  = req_slot_valid
  val resp_ptr_in_range = resp_slot_valid && (resp_offset <= req_offset)
  val safe_ptr_before_tail = safe_offset < io.count

  val req_in_safe_region = req_ptr_in_range && (req_offset < safe_offset)
  val can_send = req_in_safe_region && (req_slot_state === RobState.WaitingForMem)
  val can_accept_resp = resp_ptr_in_range && (resp_slot_state === RobState.WaitingForResult)

  when(io.flush) {
    req_ptr  := 0.U
    resp_ptr := 0.U
    safe_ptr := 0.U
  }.otherwise {
    // safe_ptr: sync to head when stale (after commit); else advance when !might_flush and before tail
    when(safe_offset >= io.count) {
      safe_ptr := io.headPtr
    }.elsewhen(safe_slot_valid && !safe_slot_might_flush && safe_ptr_before_tail) {
      safe_ptr := (safe_ptr + 1.U)(idWidth - 1, 0)
    }

    // req_ptr advance
    when(req_ptr_in_range) {
      when(req_slot_state === RobState.Executing) {
        // wait
      }.elsewhen(req_slot_state === RobState.WaitingForMem) {
        when(req_in_safe_region && io.mem.req.fire) {
          req_ptr := (req_ptr + 1.U)(idWidth - 1, 0)
        }
      }.otherwise {
        req_ptr := (req_ptr + 1.U)(idWidth - 1, 0)
      }
    }

    // resp_ptr advance
    when(resp_ptr_in_range) {
      when(resp_slot_state === RobState.Executing || resp_slot_state === RobState.WaitingForMem) {
        // wait
      }.elsewhen(resp_slot_state === RobState.WaitingForResult) {
        when(io.mem.resp.fire) {
          resp_ptr := (resp_ptr + 1.U)(idWidth - 1, 0)
        }
      }.otherwise {
        resp_ptr := (resp_ptr + 1.U)(idWidth - 1, 0)
      }
    }
  }

  io.mem.req.valid := !io.flush && can_send
  io.mem.req.bits.rob_id := req_ptr
  io.mem.req.bits.addr   := io.slotReqRead.slot.rd_value
  io.mem.req.bits.wdata  := io.slotReqRead.slot.mem_wdata
  io.mem.req.bits.wstrb  := io.slotReqRead.slot.mem_wstrb
  io.mem.req.bits.lsuOp  := io.slotReqRead.slot.mem_lsuOp
  io.mem.resp.ready := can_accept_resp
}
