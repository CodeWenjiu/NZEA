package nzea_core.backend.fu

import chisel3._
import chisel3.util.{Decoupled, Valid, Mux1H}
import nzea_core.backend.ExuOut

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

/** BRU input: target, rs1/rs2 for branch compare, bruOp (ChiselEnum); BRU derives is_jmp and is_taken internally. */
class BruInput extends Bundle {
  val target   = UInt(32.W)
  val rs1      = UInt(32.W)
  val rs2      = UInt(32.W)
  val bruOp    = BruOp()
  val pc       = UInt(32.W)
  val rd_index = UInt(5.W)
}

/** BRU: branch_taken from rs1, rs2, bruOp (Mux1H); is_jmp = JAL|JALR from bruOp; is_taken = is_jmp || branch_taken. */
class BRU extends Module {
  val io = IO(new Bundle {
    val in          = Flipped(Decoupled(new BruInput))
    val out         = Decoupled(new ExuOut)
    val pc_redirect = Output(Valid(UInt(32.W)))
  })

  val b = io.in.bits
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
  io.pc_redirect.bits  := b.target

  io.out.valid        := io.in.valid && is_jmp
  io.out.bits.rd_wen  := is_jmp
  io.out.bits.rd_addr := b.rd_index
  io.out.bits.rd_data := b.pc + 4.U

  io.in.ready := Mux(is_jmp, io.out.ready, true.B)
}
