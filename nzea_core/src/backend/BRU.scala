package nzea_core.backend

import chisel3._
import chisel3.util.{Mux1H, Valid}
import nzea_core.PipeIO
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

/** BRU input: pc, pred_next_pc, offset (imm), rs1/rs2 for branch compare, bruOp; rob_id from IS.
  * use_rs1_imm => target = (rs1+offset)&~1 else target = pc+offset. robIdWidth from upper level. */
class BruInput(robIdWidth: Int) extends Bundle {
  val pc           = UInt(32.W)
  val pred_next_pc = UInt(32.W)
  val offset       = UInt(32.W)
  val use_rs1_imm  = Bool()
  val rs1          = UInt(32.W)
  val rs2          = UInt(32.W)
  val bruOp        = BruOp()
  val rob_id       = UInt(robIdWidth.W)
}

/** BRU: combinational; writes result to Rob via rob_access. */
class BRU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new BruInput(robIdWidth)))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
  })

  val b = io.in.bits
  val target = Mux(b.use_rs1_imm, (b.rs1 + b.offset) & ~1.U(32.W), b.pc + b.offset)
  val bruOpU = b.bruOp.asUInt
  val is_jmp = bruOpU(0) || bruOpU(1)  // JAL, JALR
  val eq  = b.rs1 === b.rs2
  val ne  = b.rs1 =/= b.rs2
  val lt  = b.rs1.asSInt < b.rs2.asSInt
  val ge  = b.rs1.asSInt >= b.rs2.asSInt
  val ltu = b.rs1 < b.rs2
  val geu = b.rs1 >= b.rs2
  val branchTaken = Mux1H(bruOpU, Seq(
    true.B, true.B, eq, ne, lt, ge, ltu, geu  // JAL, JALR, BEQ, BNE, BLT, BGE, BLTU, BGEU
  ))
  val is_taken   = is_jmp || branchTaken
  val next_pc    = Mux(is_taken, target, b.pc + 4.U)
  val mispredict = b.pred_next_pc =/= next_pc
  val rd_value   = b.pc + 4.U

  val u = Rob.entryStateUpdate(io.in.valid, b.rob_id, is_done = true.B, need_mem = false.B, rd_value, mispredict, next_pc)(robIdWidth)
  io.rob_access.valid := u.valid
  io.rob_access.bits := u.bits
  io.in.ready := true.B
  io.in.flush := io.rob_access.flush
}
