package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, MuxCase}

/** RV32I immediate encoding type. */
object ImmType extends chisel3.ChiselEnum {
  val I, S, B, U, J = Value
}

/** Decoded instruction: raw inst + RV32I ImmType. */
class DecodedInst extends Bundle {
  val inst    = UInt(32.W)
  val immType = ImmType()
}

/** Instruction Decode Unit: input from IFU (inst), decodes RV32I ImmType only.
  * Uses ChiselEnum ImmType; decode is combinational from opcode inst(6,2).
  * Output is Decoupled(DecodedInst); back-pressure propagates to IFU.
  */
class IDU extends Module {
  val io = IO(new Bundle {
    val inst    = Flipped(Decoupled(UInt(32.W)))
    val decoded = Decoupled(new DecodedInst)
  })

  val opcode = io.inst.bits(6, 2)

  // RV32I: 00011 load, 00100 op-imm, 01000 store, 00101 auipc, 01101 lui,
  //        11000 branch, 11001 jalr, 11011 jal, 11100 system/csr
  val immType = MuxCase(
    ImmType.I,
    Seq(
      (opcode === "b01000".U) -> ImmType.S,  // S-type store
      (opcode === "b11000".U) -> ImmType.B,  // B-type branch
      (opcode === "b00101".U) -> ImmType.U,  // auipc
      (opcode === "b01101".U) -> ImmType.U,  // lui
      (opcode === "b11011".U) -> ImmType.J   // jal
    )
  )

  io.decoded.valid  := io.inst.valid
  io.decoded.bits.inst    := io.inst.bits
  io.decoded.bits.immType := immType
  io.inst.ready      := io.decoded.ready
}
