package nzea_core.backend

import chisel3._
import chisel3.util.{Mux1H, Valid}
import nzea_core.{PipeIO, PipelineConnect}
import nzea_core.frontend.PrfWriteBundle
import nzea_core.frontend.bp.BpUpdate
import nzea_core.retire.rob.Rob

/** BRU op: one-hot (JAL, JALR, BEQ, BNE, BLT, BGE, BLTU, BGEU). */
object BruOp extends chisel3.ChiselEnum {
  val JAL  = Value((1 << 0).U)
  val JALR = Value((1 << 1).U)
  val BEQ  = Value((1 << 2).U)
  val BNE  = Value((1 << 3).U)
  val BLT  = Value((1 << 4).U)
  val BGE  = Value((1 << 5).U)
  val BLTU = Value((1 << 6).U)
  val BGEU = Value((1 << 7).U)
}

/** BRU input: pc, pred_next_pc, offset (imm), rs1/rs2 for branch compare, bruOp; rob_id, p_rd from IS. */
class BruInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val pc           = UInt(32.W)
  val pred_next_pc = UInt(32.W)
  val offset       = UInt(32.W)
  val rs1          = UInt(32.W)
  val rs2          = UInt(32.W)
  val bruOp        = BruOp()
  val rob_id       = UInt(robIdWidth.W)
  val p_rd         = UInt(prfAddrWidth.W)
}

/** BRU stage-1 payload: resolved next_pc, flush (mispredict), is_taken; for ROB and IFU. */
class BruS1Out(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id   = UInt(robIdWidth.W)
  val p_rd     = UInt(prfAddrWidth.W)
  val pc       = UInt(32.W)
  val next_pc  = UInt(32.W)
  val flush    = Bool()
  val is_taken = Bool()
}

/** BRU Stage 0: computes target, is_taken, flush (mispredict). Outputs PipeIO(BruS1Out). */
class BRUStage0(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new BruInput(robIdWidth, prfAddrWidth)))
    val out = new PipeIO(new BruS1Out(robIdWidth, prfAddrWidth))
  })

  val b = io.in.bits
  val bruOpU = b.bruOp.asUInt
  val is_jalr = bruOpU(1)
  val target = Mux(is_jalr, b.rs1 + b.offset, b.pc + b.offset)
  val is_jmp = bruOpU(0) || bruOpU(1)
  val eq  = b.rs1 === b.rs2
  val ne  = b.rs1 =/= b.rs2
  val lt  = b.rs1.asSInt < b.rs2.asSInt
  val ge  = b.rs1.asSInt >= b.rs2.asSInt
  val ltu = b.rs1 < b.rs2
  val geu = b.rs1 >= b.rs2
  val branchTaken = Mux1H(bruOpU, Seq(
    true.B, true.B, eq, ne, lt, ge, ltu, geu
  ))
  val is_taken   = is_jmp || branchTaken
  val next_pc    = Mux(is_taken, target, b.pc + 4.U)
  val mispredict = b.pred_next_pc =/= next_pc

  io.out.valid := io.in.valid
  io.out.bits.rob_id   := b.rob_id
  io.out.bits.p_rd     := b.p_rd
  io.out.bits.pc       := b.pc
  io.out.bits.next_pc  := next_pc
  io.out.bits.flush    := mispredict
  io.out.bits.is_taken := is_taken
  io.in.ready := io.out.ready
  io.in.flush := io.out.flush
}

/** BRU Stage 1: receives BruS1Out, outputs to ROB, PRF, IFU. */
class BRUStage1(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new BruS1Out(robIdWidth, prfAddrWidth)))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
    val prf_write  = Output(Valid(new PrfWriteBundle(prfAddrWidth)))
    val bp_update  = Output(Valid(new BpUpdate))
  })

  val b = io.in.bits
  val u = Rob.entryStateUpdate(
    io.in.valid,
    b.rob_id,
    is_done = true.B,
    flush = b.flush,
    next_pc = b.next_pc
  )(robIdWidth)

  io.rob_access.valid := u.valid
  io.rob_access.bits  := u.bits

  io.prf_write.valid := u.valid && b.p_rd =/= 0.U
  io.prf_write.bits.addr := b.p_rd
  io.prf_write.bits.data := b.pc + 4.U

  io.bp_update.valid := io.in.valid
  io.bp_update.bits.pc := b.pc
  io.bp_update.bits.taken := b.is_taken
  io.bp_update.bits.target := b.next_pc

  io.in.ready := io.rob_access.ready
  io.in.flush := io.rob_access.flush
}

/** BRU: 2-stage pipeline. S0 computes; S1 outputs. PipelineConnect internally. */
class BRU(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new BruInput(robIdWidth, prfAddrWidth)))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
    val prf_write  = Output(Valid(new PrfWriteBundle(prfAddrWidth)))
    val bp_update  = Output(Valid(new BpUpdate))
  })

  val s0 = Module(new BRUStage0(robIdWidth, prfAddrWidth))
  val s1 = Module(new BRUStage1(robIdWidth, prfAddrWidth))

  io.in <> s0.io.in
  PipelineConnect(s0.io.out, s1.io.in)
  io.rob_access <> s1.io.rob_access
  io.prf_write := s1.io.prf_write
  io.bp_update := s1.io.bp_update
}
