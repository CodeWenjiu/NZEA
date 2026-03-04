package nzea_core.retire.rob

import chisel3._
import chisel3.util.{Mux1H, PriorityEncoder}
import nzea_core.backend.LsuOp

/** Memory Request Manager: handles memory request scheduling with two pointers */
class MemReqManager(depth: Int, idWidth: Int) extends Module {
  val io = IO(new Bundle {
    // Memory interface
    val mem = new RobMemIO(idWidth)

    // Slot status inputs (from Rob)
    val slotsValid = Input(Vec(depth, Bool()))
    val slotsState = Input(Vec(depth, RobState()))
    val slotsAddr = Input(Vec(depth, UInt(32.W)))
    val slotsWdata = Input(Vec(depth, UInt(32.W)))
    val slotsWstrb = Input(Vec(depth, UInt(4.W)))
    val slotsLsuOp = Input(Vec(depth, LsuOp()))

    // Control signals
    val headPtr = Input(UInt(idWidth.W))
    val count = Input(UInt((idWidth + 1).W))
    val flush = Input(Bool())
  })

  def idxFromHead(offset: UInt): UInt = (io.headPtr + offset)(idWidth - 1, 0)

  // Two pointers for memory request management
  val mem_req_ptr = RegInit(0.U(idWidth.W))
  val mem_req_valid = RegInit(false.B)
  val mem_wait_ptr = RegInit(0.U(idWidth.W))
  val mem_wait_valid = RegInit(false.B)

  // Helper functions for finding next requests
  def findNextWaitingForMem(ptr: UInt): (Bool, UInt) = {
    val candidates = (1 until depth).map { offset =>
      val idx = (ptr + offset.U)(idWidth - 1, 0)
      val allBetweenWaitingResult = (1 until offset)
        .map { k =>
          val kidx = (ptr + k.U)(idWidth - 1, 0)
          io.slotsValid(kidx) && io.slotsState(kidx) === RobState.WaitingForResult
        }
        .foldLeft(true.B)(_ && _)
      io.slotsValid(idx) && io.slotsState(idx) === RobState.WaitingForMem && allBetweenWaitingResult
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
      io.slotsValid(idx) && io.slotsState(idx) === RobState.WaitingForResult
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
          io.slotsValid(kidx) && io.slotsState(kidx) === RobState.WaitingForResult
        }
        .foldLeft(true.B)(_ && _)
      io.slotsValid(i) && io.slotsState(i) === RobState.WaitingForMem && prefixAllWaitingResult
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
      io.slotsValid(i) && io.slotsState(i) === RobState.WaitingForResult
    }
    val found = candidates.reduce(_ || _)
    val enc = PriorityEncoder(VecInit(candidates).asUInt)
    val values = (0 until depth).map(j => idxFromHead(j.U))
    val idx = Mux1H((0 until depth).map(i => enc === i.U), values)
    (found, idx)
  }

  // Memory request/response logic
  when(io.flush) {
    mem_req_valid := false.B
    mem_wait_valid := false.B
  }.otherwise {
    // Handle memory request firing
    when(io.mem.req.fire) {
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

    // Handle memory response
    when(io.mem.resp.fire) {
      mem_wait_valid := false.B
      val (respHasNext, respNextPtr) = findNextWaitingForResult(io.mem.resp.bits.rob_id)
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

  // Memory request outputs
  val mem_req_slot_addr = io.slotsAddr(mem_req_ptr)
  val mem_req_slot_wdata = io.slotsWdata(mem_req_ptr)
  val mem_req_slot_wstrb = io.slotsWstrb(mem_req_ptr)
  val mem_req_slot_lsuOp = io.slotsLsuOp(mem_req_ptr)

  io.mem.req.valid := !io.flush && mem_req_valid
  io.mem.req.bits.rob_id := mem_req_ptr
  io.mem.req.bits.addr := mem_req_slot_addr
  io.mem.req.bits.wdata := mem_req_slot_wdata
  io.mem.req.bits.wstrb := mem_req_slot_wstrb
  io.mem.req.bits.lsuOp := mem_req_slot_lsuOp
  io.mem.resp.ready := true.B
}
