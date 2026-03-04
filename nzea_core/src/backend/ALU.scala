package nzea_core.backend

import chisel3._
import chisel3.util.{Mux1H, Valid}
import nzea_core.PipeIO
import nzea_core.retire.rob.{Rob, RobState}

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

/** ALU FU input: operands, ALU ctrl; pc for AUIPC; rob_id from IS. robIdWidth from upper level. */
class AluInput(robIdWidth: Int) extends Bundle {
  val opA    = UInt(32.W)
  val opB    = UInt(32.W)
  val aluOp  = AluOp()
  val pc     = UInt(32.W)
  val rob_id = UInt(robIdWidth.W)
}

/** ALU FU: combinational; writes result to Rob via rob_access. */
class ALU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new AluInput(robIdWidth)))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
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

  val u = Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, RobState.Done, result)(robIdWidth)
  io.rob_access.valid := u.valid
  io.rob_access.bits := u.bits
  io.in.ready := true.B
  io.in.flush := io.rob_access.flush
}
