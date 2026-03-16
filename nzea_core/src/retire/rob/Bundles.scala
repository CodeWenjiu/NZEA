package nzea_core.retire.rob

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.backend.LsuOp
import nzea_core.frontend.CsrType

// -------- Mem type for ROB slot (set at ISU dispatch) --------

object RobMemType extends chisel3.ChiselEnum {
  val None  = Value(0.U(2.W))
  val Load  = Value(1.U(2.W))
  val Store = Value(2.U(2.W))

  def needMem(t: RobMemType.Type): Bool = t =/= None
  def isLoad(t: RobMemType.Type): Bool  = t === Load
}

// -------- Bundles --------

/** One entry in the Rob. Mem addr/wdata/wstrb in MemUnit LsBuffer. */
class RobEntry extends Bundle {
  val rd_index  = UInt(5.W)
  val is_done   = Bool()
  val mem_type  = RobMemType()
  val rd_value  = UInt(32.W)
  val next_pc   = UInt(32.W)
  val flush     = Bool()
}

/** Payload for Rob enq: rd_index, might_flush (branch/trap), mem_type (Load/Store/None), p_rd, old_p_rd. */
class RobEnqPayload(prfAddrWidth: Int) extends Bundle {
  val rd_index    = UInt(5.W)
  val might_flush = Bool()
  val mem_type   = RobMemType()
  val p_rd       = UInt(prfAddrWidth.W)
  val old_p_rd   = UInt(prfAddrWidth.W)
}

/** MemUnit request / LS_Queue entry: rob_id, addr, wdata, wstrb, lsuOp, p_rd (for load PRF write). */
class RobMemReq(idWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id = UInt(idWidth.W)
  val addr   = UInt(32.W)
  val wdata  = UInt(32.W)
  val wstrb  = UInt(4.W)
  val lsuOp  = LsuOp()
  val p_rd   = UInt(prfAddrWidth.W)
}

/** LS_Queue allocation: at dispatch, request ls_id. Bits: rob_id, p_rd, lsuOp (written to slot at alloc). */
class LsAllocReq(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
  val lsuOp  = UInt(LsuOp.getWidth.W)
}

/** LS_Queue write: AGU writes addr/wdata/wstrb to slot[lsq_id] and sets data_ready. */
class LsWriteReq(lsqIdWidth: Int) extends Bundle {
  val lsq_id = UInt(lsqIdWidth.W)
  val addr   = UInt(32.W)
  val wdata  = UInt(32.W)
  val wstrb  = UInt(4.W)
}

/** MemUnit response: rob_id, data (load result; store ignores). */
class RobMemResp(idWidth: Int) extends Bundle {
  val rob_id = UInt(idWidth.W)
  val data   = UInt(32.W)
}

/** Unified Rob slot read: all fields any consumer may need.
  * ISU: valid, is_done. Mem data in LS_Queue. rd_value from PRF(p_rd) at commit.
  */
class RobSlotRead extends Bundle {
  val valid       = Bool()
  val is_done     = Bool()
  val mem_type    = RobMemType()
  val might_flush = Bool()
}

/** Unified slot read port: consumer provides rob_id, Rob returns slot. Use Flipped on consumer side. */
class RobSlotReadPort(idWidth: Int) extends Bundle {
  val rob_id = Input(UInt(idWidth.W))
  val slot   = Output(new RobSlotRead)
}

/** FU output to Rob: state update for an entry. Mem data (addr,wdata,wstrb) goes to LS_Queue.
  * rd_value not stored in Rob; commit reads from PRF(p_rd).
  * mem_type set at enq (ISU), not updated by FU.
  * csr_type/csr_data: when SYSU writes CSR, set at completion; CsrType.None otherwise.
  */
class RobEntryStateUpdate(idWidth: Int) extends Bundle {
  val rob_id    = UInt(idWidth.W)
  val is_done   = Bool()
  val flush     = Bool()
  val next_pc   = UInt(32.W)
  val csr_type  = CsrType()
  val csr_data  = UInt(32.W)
}

/** FU output to Rob: valid/bits from FU. Flush comes from WBU via EXU. */
class RobAccessIO(idWidth: Int) extends Bundle {
  val valid = Output(Bool())
  val bits  = Output(new RobEntryStateUpdate(idWidth))
}

/** ROB enq IO: req (consumer side), rob_id (from Rob). Use Flipped for producer (e.g. ISU). */
class RobEnqIO(idWidth: Int, prfAddrWidth: Int) extends Bundle {
  val req    = Flipped(Decoupled(new RobEnqPayload(prfAddrWidth)))
  val rob_id = Output(UInt(idWidth.W))
}

/** Rob–MemUnit: Rob issues mem request; MemUnit provides issue_rob_id, resp. AGU stalls internally when LS queue full. */
class RobMemIO(idWidth: Int) extends Bundle {
  val issue        = Output(Bool())
  val issue_rob_id = Input(Valid(UInt(idWidth.W)))
  val flush        = Output(Bool())
  val resp         = Flipped(Decoupled(new RobMemResp(idWidth)))
}

class RobAccessPortsIO(idWidth: Int, numPorts: Int) extends Bundle {
  val accessPorts = Vec(numPorts, Flipped(new RobAccessIO(idWidth)))
}
