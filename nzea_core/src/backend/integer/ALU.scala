package nzea_core.backend.integer

import chisel3._
import chisel3.util.{Fill, Mux1H, Valid}
import nzea_rtl.{MuxTree, PipeIO, PrefixAdder}
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

/** ALU op: one-hot for Mux1H (add, sub, and, or, xor, sll, srl, sra, slt, sltu). */
object AluOp extends chisel3.ChiselEnum {
  val Add = Value((1 << 0).U)
  val Sub = Value((1 << 1).U)
  val And = Value((1 << 2).U)
  val Or = Value((1 << 3).U)
  val Xor = Value((1 << 4).U)
  val Sll = Value((1 << 5).U)
  val Srl = Value((1 << 6).U)
  val Sra = Value((1 << 7).U)
  val Slt = Value((1 << 8).U)
  val Sltu = Value((1 << 9).U)
}

/** ALU FU input: operands, ALU ctrl; pc for AUIPC; rob_id, p_rd from IS. */
class AluInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val opA = UInt(32.W)
  val opB = UInt(32.W)
  val aluOp = AluOp()
  val pc = UInt(32.W)
  val rob_id = UInt(robIdWidth.W)
  val p_rd = UInt(prfAddrWidth.W)
}

/** ALU FU: combinational; writes result to Rob (commit) and PRF (direct). */
class ALU(robIdWidth: Int, prfAddrWidth: Int) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new AluInput(robIdWidth, prfAddrWidth)))
    val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
    val out = new PipeIO(new PrfWriteBundle(prfAddrWidth))
  })

  val opA = io.in.bits.opA
  val opB = io.in.bits.opB
  val aluOp = io.in.bits.aluOp
  val shamt = opB(4, 0)

  val add = PrefixAdder(opA, opB)
  val sub = PrefixAdder(opA, ~opB, true.B)
  val and = opA & opB
  val or = opA | opB
  val xor = opA ^ opB
  // Tree shifters instead of binary barrel shifters: a binary barrel fans the
  // shamt bits out to 32 muxes per stage (fanout 32 per bit); a MuxTree applies
  // constant shifts for free (pure rewiring) and each shamt bit selects at most
  // 16/8/4/2/1 muxes as the tree narrows, balancing select fanout down 5 levels.
  val sll = MuxTree(shamt, (0 until 32).map(i => opA << i))
  val srl = MuxTree(shamt, (0 until 32).map(i => opA >> i))
  // Arithmetic right shift: CIRCT constant-folds `asUInt(shr(sint, const))`
  // into a logical shift (sign bit lost) — verified against firtool 1.147 and
  // the microbench sieve difftest (srai a1,a0,0x11: ref 0xffffde70 vs dut
  // 0x00005e70). So MuxTree over SInt constant shifts is broken. Derive sra
  // from srl + explicit sign-fill: when the sign bit is set, OR in the
  // shifted-out high bits (mask is a MuxTree of constant literals, pure
  // rewiring — no dynamic barrel shifter on the critical path).
  val sraMask = MuxTree(shamt, (0 until 32).map(i => if (i == 0) 0.U(32.W) else 0xffffffffL.U(32.W) << (32 - i)))
  val sra = srl | (sraMask & Fill(32, opA(31)))
  val slt = Mux(opA.asSInt < opB.asSInt, 1.U(32.W), 0.U(32.W))
  val sltu = Mux(opA < opB, 1.U(32.W), 0.U(32.W))

  // Group the ten results into four classes, mux inside each class, then a
  // 4:1 tree on top: the flat 10:1 one-hot select fans out to ~320 loads,
  // which dominated the result path after the prefix adders were sped up.
  // Per-class selects fan out only within their class; the top tree sees just
  // four class-select bits.
  val aluOpU = aluOp.asUInt
  val arith = Mux(aluOpU(1), sub, add)
  val logic = Mux1H(aluOpU(4, 2), Seq(and, or, xor))
  val shift = Mux1H(aluOpU(7, 5), Seq(sll, srl, sra))
  val cmp = Mux(aluOpU(9), sltu, slt)

  val result = Mux1H(
    Seq(
      aluOpU(0) || aluOpU(1),
      aluOpU(2) || aluOpU(3) || aluOpU(4),
      aluOpU(5) || aluOpU(6) || aluOpU(7),
      aluOpU(8) || aluOpU(9)
    ),
    Seq(arith, logic, shift, cmp)
  )

  val next_pc = io.in.bits.pc + 4.U
  io.rob_access <> Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, is_done = true.B, next_pc = next_pc)(robIdWidth)
  io.in.ready := io.out.ready

  io.out.valid := io.in.valid
  io.out.bits.addr := io.in.bits.p_rd
  io.out.bits.data := result
  io.in.flush := io.out.flush
}
