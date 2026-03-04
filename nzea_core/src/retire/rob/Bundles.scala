package nzea_core.retire.rob

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.backend.LsuOp

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

/** Completion event: rob_id and rd for clearing stallTable (delayed by 1 cycle). */
class CompletionEvent(idWidth: Int) extends Bundle {
  val rob_id = UInt(idWidth.W)
  val rd     = UInt(5.W)
}

/** ROB IO interfaces */
class RobEnqIO extends Bundle {
  val req    = Flipped(Decoupled(new RobEnqPayload))
  val rob_id = Output(UInt(32.W)) // Will be sized properly in Rob
}

class RobMemIO(idWidth: Int) extends Bundle {
  val req  = Decoupled(new RobMemReq(idWidth))
  val resp = Flipped(Decoupled(new RobMemResp(idWidth)))
}

class RobRatIO extends Bundle {
  val req  = Input(new RatReq)
  val resp = Output(new RatResp)
}

class RobCommitIO extends Bundle {
  val commit = Decoupled(new RobCommitInfo)
}

class RobAccessPortsIO(idWidth: Int, numPorts: Int) extends Bundle {
  val accessPorts = Vec(numPorts, Flipped(new RobAccessIO(idWidth)))
}
