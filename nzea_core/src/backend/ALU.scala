package nzea_core.backend

import chisel3._
import chisel3.util.{Mux1H, Valid}
import nzea_core.PipeIO
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

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

/** ALU FU input: operands, ALU ctrl; pc for AUIPC; rob_id, p_rd from IS. */
class AluInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val opA    = UInt(32.W)
  val opB    = UInt(32.W)
  val aluOp  = AluOp()
  val pc     = UInt(32.W)
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
}

/** ALU FU: combinational; writes result to Rob (commit) and PRF (direct). */
class ALU(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new AluInput(robIdWidth, prfAddrWidth)))
    val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
    val out  = new PipeIO(new PrfWriteBundle(prfAddrWidth))
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

  val next_pc = io.in.bits.pc + 4.U
  io.rob_access <> Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, is_done = true.B, next_pc = next_pc)(robIdWidth)
  io.in.ready := io.out.ready

  io.out.valid := io.in.valid
  io.out.bits.addr := io.in.bits.p_rd
  io.out.bits.data := result
  io.in.flush := io.out.flush
}
