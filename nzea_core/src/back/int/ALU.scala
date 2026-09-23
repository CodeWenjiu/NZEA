package nzea_core.back.int

import chisel3._
import chisel3.util.{Fill, Mux1H, Valid}
import nzea_rtl.{MuxTree, PipeIO, PrefixAdder}
import nzea_core.back.FuCompletion
import nzea_core.back.PrfWriteBundle
import nzea_core.isa.AluOp

/** ALU FU input: operands, ALU ctrl; pc for AUIPC; tag, waddr from IS. */
class AluInput(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val opA = UInt(32.W)
  val opB = UInt(32.W)
  val aluOp = AluOp()
  val pc = UInt(32.W)
  val tag = UInt(tagWidth.W)
  val waddr = UInt(waddrWidth.W)
}

/** ALU FU: combinational; writes result to Rob (commit) and PRF (direct). */
class ALU(tagWidth: Int, waddrWidth: Int) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new AluInput(tagWidth, waddrWidth)))
    val completion = Output(Valid(new FuCompletion(tagWidth)))
    val out = new PipeIO(new PrfWriteBundle(waddrWidth))
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
  io.completion <> FuCompletion.report(io.in.valid, io.in.bits.tag, is_done = true.B, next_pc = next_pc)(tagWidth)
  io.in.ready := io.out.ready

  io.out.valid := io.in.valid
  io.out.bits.waddr := io.in.bits.waddr
  io.out.bits.data := result
  io.in.flush := io.out.flush
}
