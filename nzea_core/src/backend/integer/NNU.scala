package nzea_core.backend.integer

import chisel3._
import chisel3.util.Valid
import nzea_core.PipeIO
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

/** WJCUS0 custom-0 NN ops: NN_LOAD_ACT (R), NN_START (I), NN_LOAD (I). Stub FU; behavior TBD. */
object NnOp extends chisel3.ChiselEnum {
  val LoadAct = Value((1 << 0).U)
  val Start   = Value((1 << 1).U)
  val Load    = Value((1 << 2).U)
}

class NnInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val nnOp   = NnOp()
  val rs1    = UInt(32.W)
  val rs2    = UInt(32.W)
  val pc     = UInt(32.W)
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
}

/** Single-cycle placeholder: completes ROB; NN_LOAD writes a dummy PRF result (real NN logic later). */
class NNU(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new NnInput(robIdWidth, prfAddrWidth)))
    val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
    val out        = new PipeIO(new PrfWriteBundle(prfAddrWidth))
  })

  val next_pc = io.in.bits.pc + 4.U
  io.rob_access <> Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, is_done = true.B, next_pc = next_pc)(robIdWidth)
  io.in.ready := io.out.ready

  val stubLoad = io.in.bits.rs1 ^ io.in.bits.rs2
  io.out.valid := io.in.valid
  io.out.bits.addr := io.in.bits.p_rd
  io.out.bits.data := Mux(io.in.bits.nnOp === NnOp.Load, stubLoad, 0.U(32.W))
  io.in.flush := io.out.flush
}
