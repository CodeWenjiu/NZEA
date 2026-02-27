package nzea_core.backend.fu

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.Mux1H
/** ALU write-back payload (rd_index from commit queue). */
class AluOut extends Bundle {
  val rd_data = UInt(32.W)
}

/** ALU op: one-hot for Mux1H (add, sub, and, or, xor, sll, srl, sra, slt, sltu). */
object AluOp extends chisel3.ChiselEnum {
  val Add = Value((1 << 0).U)
  val Sub = Value((1 << 1).U)
  val And = Value((1 << 2).U)
  val Or  = Value((1 << 3).U)
  val Xor = Value((1 << 4).U)
  val Sll = Value((1 << 5).U)
  val Srl = Value((1 << 6).U)
  val Sra = Value((1 << 7).U)
  val Slt = Value((1 << 8).U)
  val Sltu = Value((1 << 9).U)
}

/** ALU FU input: operands, ALU ctrl (ChiselEnum from IS). rd_index carried for commit queue only. */
class AluInput extends Bundle {
  val opA      = UInt(32.W)
  val opB      = UInt(32.W)
  val aluOp    = AluOp()
  val rd_index = UInt(5.W)
}

/** ALU FU: opA/opB/aluOp/rd_index in, AluOut to WBU. */
class ALU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new AluInput))
    val out = Decoupled(new AluOut)
  })

  val opA   = io.in.bits.opA
  val opB   = io.in.bits.opB
  val aluOp = io.in.bits.aluOp
  val shamt = opB(4, 0)

  val add = opA + opB
  val sub = opA - opB
  val and = opA & opB
  val or  = opA | opB
  val xor = opA ^ opB
  val sll = opA << shamt
  val srl = opA >> shamt
  val sra = (opA.asSInt >> shamt).asUInt
  val slt  = Mux(opA.asSInt < opB.asSInt, 1.U(32.W), 0.U(32.W))
  val sltu = Mux(opA < opB, 1.U(32.W), 0.U(32.W))

  val result = Mux1H(aluOp.asUInt, Seq(add, sub, and, or, xor, sll, srl, sra, slt, sltu))

  io.out.valid        := io.in.valid
  io.out.bits.rd_data := result
  io.in.ready := io.out.ready
}
