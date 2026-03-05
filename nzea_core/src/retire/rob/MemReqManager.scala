package nzea_core.retire.rob

import chisel3._

/** Memory Request Manager: two pointers (req_ptr, resp_ptr) with explicit advance logic.
  * Uses two read ports instead of full slots - one for req slot, one for resp slot.
  *
  * req_ptr: points to slot to consider for sending mem request.
  *   - In range: [head, tail), else don't advance.
  *   - Executing: don't advance (unknown if mem op).
  *   - WaitingForMem: send only when req_ptr==head (oldest); on mem.req.fire, advance.
  *   - WaitingForResult/Done: advance (skip).
  *
  * resp_ptr: points to slot to consider for receiving mem response.
  *   - In range: [head, req_ptr], else don't advance.
  *   - Executing/WaitingForMem: wait (don't advance).
  *   - WaitingForResult: on mem.resp.fire, advance.
  *   - Done: advance (skip).
  */
class MemReqManager(depth: Int, idWidth: Int) extends Module {
  val io = IO(new Bundle {
    val mem = new RobMemIO(idWidth)
    val slotReqRead  = Flipped(new RobSlotReadPort(idWidth))
    val slotRespRead = Flipped(new RobSlotReadPort(idWidth))
    val headPtr     = Input(UInt(idWidth.W))
    val flush       = Input(Bool())
  })

  val req_ptr  = RegInit(0.U(idWidth.W))
  val resp_ptr = RegInit(0.U(idWidth.W))

  // Drive rob_id for read ports (combinational)
  io.slotReqRead.rob_id  := req_ptr
  io.slotRespRead.rob_id := resp_ptr

  val req_slot_valid  = io.slotReqRead.slot.valid
  val req_slot_state  = io.slotReqRead.slot.rob_state
  val resp_slot_valid = io.slotRespRead.slot.valid
  val resp_slot_state = io.slotRespRead.slot.rob_state

  // req_ptr in [head, tail): slotValid(req_ptr)
  val req_ptr_in_range = req_slot_valid
  // resp_ptr in [head, req_ptr]: valid and (resp_ptr-head) <= (req_ptr-head) in circular order
  val resp_offset = (resp_ptr - io.headPtr)(idWidth - 1, 0)
  val req_offset  = (req_ptr - io.headPtr)(idWidth - 1, 0)
  val resp_ptr_in_range = resp_slot_valid && (resp_offset <= req_offset)

  val can_send = req_ptr_in_range && (req_ptr === io.headPtr) && (req_slot_state === RobState.WaitingForMem)
  val can_accept_resp = resp_ptr_in_range && (resp_slot_state === RobState.WaitingForResult)

  when(io.flush) {
    req_ptr  := 0.U
    resp_ptr := 0.U
  }.otherwise {
    // req_ptr advance
    when(req_ptr_in_range) {
      when(req_slot_state === RobState.Executing) {
        // wait
      }.elsewhen(req_slot_state === RobState.WaitingForMem) {
        when(req_ptr === io.headPtr && io.mem.req.fire) {
          req_ptr := (req_ptr + 1.U)(idWidth - 1, 0)
        }
      }.otherwise {
        // WaitingForResult or Done: advance
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
        // Done: advance
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
