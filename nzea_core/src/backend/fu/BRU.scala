package nzea_core.backend.fu

import chisel3._
import chisel3.util.{Decoupled, Valid, Mux1H}
/** BRU write-back payload (rd_index from commit queue). */
class BruOut extends Bundle {
  val rd_data = UInt(32.W)
  val next_pc = UInt(32.W)
}

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

/** BRU input: pc, offset (imm), rs1/rs2 for branch compare, bruOp; use_rs1_imm => target = (rs1+offset)&~1 else target = pc+offset. */
class BruInput extends Bundle {
  val pc          = UInt(32.W)
  val offset      = UInt(32.W)
  val use_rs1_imm = Bool()
  val rs1         = UInt(32.W)
  val rs2         = UInt(32.W)
  val bruOp       = BruOp()
}

/** BRU: branch_taken from rs1, rs2, bruOp (Mux1H); is_jmp = JAL|JALR from bruOp; is_taken = is_jmp || branch_taken. */
class BRU extends Module {
  val io = IO(new Bundle {
    val in          = Flipped(Decoupled(new BruInput))
    val out         = Decoupled(new BruOut)
    val pc_redirect = Output(Valid(UInt(32.W)))
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
  val is_taken = is_jmp || branchTaken

  io.pc_redirect.valid := io.in.valid && is_taken
  io.pc_redirect.bits  := target

  io.out.valid        := io.in.valid
  io.out.bits.rd_data := b.pc + 4.U
  io.out.bits.next_pc := Mux(is_taken, target, b.pc + 4.U)

  io.in.ready := io.out.ready
}
