package nzea_core.backend.fu

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.Mux1H
import nzea_core.backend.ExuOut

/** ALU op: one-hot for Mux1H (add, sub, and, or, xor, sll, srl, sra). */
object AluOp extends chisel3.ChiselEnum {
  val Add = Value((1 << 0).U)
  val Sub = Value((1 << 1).U)
  val And = Value((1 << 2).U)
  val Or  = Value((1 << 3).U)
  val Xor = Value((1 << 4).U)
  val Sll = Value((1 << 5).U)
  val Srl = Value((1 << 6).U)
  val Sra = Value((1 << 7).U)
}

/** ALU FU input: operands, ALU ctrl (ChiselEnum from IS), rd index. */
class AluInput extends Bundle {
  val opA      = UInt(32.W)
  val opB      = UInt(32.W)
  val aluOp    = AluOp()
  val rd_index = UInt(5.W)
}

/** ALU FU: opA/opB/aluOp/rd_index in, rd_index + rd_data out to WBU. */
class ALU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new AluInput))
    val out = Decoupled(new ExuOut)
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

  val result = Mux1H(aluOp.asUInt, Seq(add, sub, and, or, xor, sll, srl, sra))

  io.out.valid       := io.in.valid
  io.out.bits.rd_wen := io.in.valid
  io.out.bits.rd_addr := io.in.bits.rd_index
  io.out.bits.rd_data := result
  io.in.ready := io.out.ready
}
