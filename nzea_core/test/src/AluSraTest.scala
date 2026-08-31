package nzea_core

import chisel3._
import chiseltest._
import nzea_core.backend.integer.{ALU, AluOp}
import nzea_config.core.BpuConfig
import nzea_config.core.CoreConfig
import org.scalatest.flatspec.AnyFlatSpec

/** ALU arithmetic right shift (sra) must sign-fill. Regression test for the MuxTree shifter bug: CIRCT constant-folds
  * `asUInt(shr(sint, const))` into a logical shift, so `srai a1, a0, 0x11` produced 0x00005e70 instead of 0xffffde70 in
  * microbench sieve (difftest vs remu).
  */
class AluSraTest extends AnyFlatSpec with ChiselScalatestTester {

  private implicit val config: CoreConfig = CoreConfig(
    isa = "riscv32i",
    defaultPc = 0x8000_0000L,
    robDepth = 16,
    issueQueueDepth = 4,
    prfDepth = 64,
    vlen = 128,
    vrfDepth = 64,
    viqDepth = 8,
    bpu = BpuConfig.typical,
    sim = false
  )

  private def sraRef(x: BigInt, sh: Int): BigInt = {
    val sign = (x >> 31) & 1
    var r = x >> sh
    if (sign == 1) r |= (BigInt(0xffffffffL) << (32 - sh)) & 0xffffffffL
    r & 0xffffffffL
  }

  "ALU sra" should "sign-fill on arithmetic right shift" in {
    test(new ALU(4, 6)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in.bits.aluOp.poke(AluOp.Sra)
      dut.io.in.bits.pc.poke(0x80000000L.U)
      dut.io.in.bits.rob_id.poke(0.U)
      dut.io.in.bits.p_rd.poke(0.U)

      val cases = Seq(
        (BigInt("bce155ae", 16), 17),
        (BigInt("bce155ae", 16), 7),
        (BigInt("7fffffff", 16), 17),
        (BigInt("80000000", 16), 31),
        (BigInt("80000000", 16), 1),
        (BigInt("00000001", 16), 31),
        (BigInt("00000000", 16), 0),
        (BigInt("ffffffff", 16), 4)
      )
      for ((opA, sh) <- cases) {
        dut.io.in.bits.opA.poke(opA)
        dut.io.in.bits.opB.poke(sh)
        val ref = sraRef(opA, sh)
        dut.io.out.bits.data.expect(ref)
      }
    }
  }

}
