package nzea_core.backend.fu

import chisel3._
import chisel3.util.{Mux1H, Valid}
import nzea_core.PipeIO
import nzea_core.backend.{Rob, RobState}
/** ALU write-back payload: rd_data, rob_id, rob_entry_access (from Rob.entryStateUpdate). */
class AluOut(robIdWidth: Int) extends Bundle {
  val rd_data         = UInt(32.W)
  val rob_id          = UInt(robIdWidth.W)
  val rob_entry_access = Valid(new nzea_core.backend.RobEntryStateUpdate(robIdWidth))
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

/** ALU FU input: operands, ALU ctrl; pc for AUIPC; rob_id from IS. robIdWidth from upper level. */
class AluInput(robIdWidth: Int) extends Bundle {
  val opA    = UInt(32.W)
  val opB    = UInt(32.W)
  val aluOp  = AluOp()
  val pc     = UInt(32.W)
  val rob_id = UInt(robIdWidth.W)
}

/** ALU FU: opA/opB/aluOp in, AluOut to WBU. robIdWidth from upper level. */
class ALU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new AluInput(robIdWidth)))
    val out = new PipeIO(new AluOut(robIdWidth))
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

  io.out.valid := io.in.valid
  io.out.bits.rd_data := result
  io.out.bits.rob_id := io.in.bits.rob_id
  io.out.bits.rob_entry_access := Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, RobState.Done)(robIdWidth)
  io.in.ready         := io.out.ready
  io.in.flush          := io.out.flush
}
