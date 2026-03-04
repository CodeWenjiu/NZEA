package nzea_core.retire.rob

import chisel3._
import chisel3.util.{Decoupled, Mux1H, PriorityEncoder}

/** RAT (Register Alias Table) Entry */
class RatEntry(idWidth: Int) extends Bundle {
  val valid  = Bool()
  val rob_id = UInt(idWidth.W)
}

/** RAT Module: manages register aliasing and stall state */
class Rat(depth: Int, idWidth: Int, numAccessPorts: Int) extends Module {
  val io = IO(new Bundle {
    // RAT lookup interface
    val rat = new RobRatIO

    // Completion event processing
    val completionQueueEnq = Flipped(Decoupled(new CompletionEvent(idWidth)))
    val completionQueueDeq = Decoupled(new CompletionEvent(idWidth))

    // RAT update inputs (from Rob)
    val enqValid = Input(Bool())
    val enqRd = Input(UInt(5.W))
    val enqRobId = Input(UInt(idWidth.W))

    val commitValid = Input(Bool())
    val commitRd = Input(UInt(5.W))
    val commitNextWriterValid = Input(Bool())
    val commitNextWriterRobId = Input(UInt(idWidth.W))
    val commitEnqSameRd = Input(Bool())  // enq.fire && enq.rd_index === commitRd

    val flush = Input(Bool())
  })

  // RAT table and stall table
  val ratTable = RegInit(VecInit(Seq.fill(32)({
    val e = Wire(new RatEntry(idWidth))
    e.valid := false.B
    e.rob_id := 0.U
    e
  })))
  val stallTable = RegInit(VecInit(Seq.fill(32)(false.B)))

  // Completion queue for delayed stall clearing
  val completionQueue = Module(new chisel3.util.Queue(new CompletionEvent(idWidth), 4))
  completionQueue.io.enq <> io.completionQueueEnq
  io.completionQueueDeq <> completionQueue.io.deq

  completionQueue.io.deq.ready := false.B

  // Flush handling
  when(io.flush) {
    for (i <- 0 until 32) {
      ratTable(i).valid := false.B
      stallTable(i) := false.B
    }
  }.otherwise {
    completionQueue.io.deq.ready := true.B
    // Process completion events (delayed stall clearing)
    when(completionQueue.io.deq.fire) {
      val event = completionQueue.io.deq.bits
      val rd = event.rd
      when(rd =/= 0.U) {
        stallTable(rd) := false.B
      }
    }

    // RAT updates on enq
    when(io.enqValid) {
      val rd = io.enqRd
      when(rd =/= 0.U && !ratTable(rd).valid) {
        ratTable(rd).valid := true.B
        ratTable(rd).rob_id := io.enqRobId
        stallTable(rd) := true.B
      }
    }

    // RAT updates on commit
    when(io.commitValid) {
      val rd = io.commitRd
      when(rd =/= 0.U) {
        when(io.commitNextWriterValid) {
          ratTable(rd).valid := true.B
          ratTable(rd).rob_id := io.commitNextWriterRobId
          stallTable(rd) := true.B
        }.elsewhen(io.commitEnqSameRd) {
          ratTable(rd).valid := true.B
          ratTable(rd).rob_id := io.enqRobId
          stallTable(rd) := true.B
        }.otherwise {
          ratTable(rd).valid := false.B
          stallTable(rd) := false.B
        }
      }
    }
  }

  // RAT lookup for stall check and bypass
  def ratLookup(req: RatReq): RatResp = {
    val resp = Wire(new RatResp)
    def lookup(rsIndex: UInt, rsData: UInt): (Bool, UInt) = {
      val needStall = rsIndex =/= 0.U && ratTable(rsIndex).valid && stallTable(rsIndex)
      val bypassVal = Mux(
        rsIndex === 0.U,
        0.U(32.W),
        Mux(
          ratTable(rsIndex).valid && !stallTable(rsIndex),
          0.U, // This will be filled by Rob with slot data
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

  io.rat.resp := ratLookup(io.rat.req)
}
