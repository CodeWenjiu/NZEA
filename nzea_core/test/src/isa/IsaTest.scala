package nzea_core.isa

import nzea_rtl.stdlib.Opcode
import utest.*

/** The opcode tables the decode stage and the FUs both depend on: widths and (one-hot) encodings are load bearing, so
  * they are pinned here rather than left implicit in the consumers.
  */
object IsaTest extends TestSuite:

  /** A one-hot set: literals are `1 << i` in declaration order. */
  private def checkOneHot(values: Seq[Opcode], expectedWidth: Int): Unit =
    assert(values.map(_.lit) == values.indices.map(1 << _))
    assert(Opcode.width(values) == expectedWidth)

  /** A sequential set: literals are `0, 1, 2, ...` in declaration order. */
  private def checkSequential(values: Seq[Opcode], expectedWidth: Int): Unit =
    assert(values.map(_.lit) == values.indices)
    assert(Opcode.width(values) == expectedWidth)

  val tests = Tests:
    test("one-hot opcode sets keep their legacy encodings"):
      checkOneHot(AluOp.values.toSeq, 10)
      checkOneHot(BruOp.values.toSeq, 8)
      checkOneHot(LsuOp.values.toSeq, 8)
      checkOneHot(MulOp.values.toSeq, 4)
      checkOneHot(DivOp.values.toSeq, 4)
      checkOneHot(AluSrc.values.toSeq, 4)
      checkOneHot(BruSrc.values.toSeq, 2)
      checkOneHot(LsuSrc.values.toSeq, 1)

    test("sequential opcode sets keep their legacy encodings"):
      checkSequential(FuType.values.toSeq, 3)
      checkSequential(SysuOp.values.toSeq, 4)
      checkSequential(CsrType.values.toSeq, 3)

    test("unified fu fields are wide enough for every FU encoding"):
      assert(FuOpWidth.Width == 10)
      assert(FuSrcWidth.Width == 4)
      for op <- Seq(AluOp.values, BruOp.values, LsuOp.values, MulOp.values, DivOp.values, SysuOp.values)
      do assert(op.map(_.lit).max < (1 << FuOpWidth.Width))
      for src <- Seq(AluSrc.values, BruSrc.values, LsuSrc.values)
      do assert(src.map(_.lit).max < (1 << FuSrcWidth.Width))

    test("CSR addresses match the machine-mode map"):
      assert(CsrType.MSTATUS_ADDR == 0x300)
      assert(CsrType.MTVEC_ADDR == 0x305)
      assert(CsrType.MSCRATCH_ADDR == 0x340)
      assert(CsrType.MEPC_ADDR == 0x341)
      assert(CsrType.MCAUSE_ADDR == 0x342)
