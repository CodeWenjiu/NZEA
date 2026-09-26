package nzea_core.back

import nzea_core.back.int.{AluOp, AluSrc, BruOp, BruSrc, DivOp, FuOpWidth, FuSrcWidth, FuType, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, LsuSrc, MemOp}
import nzea_rtl.stdlib.*
import utest.*

/** The opcode tables the decode stage and the backends share: widths and (one-hot) encodings are load bearing, so they
  * are pinned here rather than left implicit in the consumers. Each backend owns its own table and its own window in
  * the shared op field.
  */
object BackTest extends TestSuite:

  /** A one-hot set: literals are `1 << i` in declaration order. */
  private def checkOneHot(values: Seq[Opcode], expectedWidth: Int): Unit =
    assert(values.map(_.lit) == values.indices.map(1 << _))
    assert(Opcode.width(values) == expectedWidth)

  /** A sequential set: literals are `0, 1, 2, ...` in declaration order. */
  private def checkSequential(values: Seq[Opcode], expectedWidth: Int): Unit =
    assert(values.map(_.lit) == values.indices)
    assert(Opcode.width(values) == expectedWidth)

  /** What a backend reads back out of a window: the bits the decode side packed into that column. */
  private def unpack(window: Int, column: OpColumn): Int =
    (window >> column.offset) & ((1 << column.width) - 1)

  val tests = Tests:
    test("the int path's unit select and source sets keep their encodings"):
      checkSequential(FuType.values.toSeq, 3)
      checkOneHot(AluSrc.values.toSeq, 4)
      checkOneHot(BruSrc.values.toSeq, 2)
      assert(FuSrcWidth.Width == 4)

    test("the int path's op sets keep their encodings"):
      checkOneHot(AluOp.values.toSeq, 10)
      checkOneHot(BruOp.values.toSeq, 8)
      checkOneHot(MulOp.values.toSeq, 4)
      checkOneHot(DivOp.values.toSeq, 4)
      checkSequential(SysuOp.values.toSeq, 4)
      assert(FuOpWidth.Width == 10)

    test("the memory path's op and source sets keep their encodings"):
      checkOneHot(LsuOp.values.toSeq, 8)
      checkOneHot(LsuSrc.values.toSeq, 1)

    test("the int window separates its columns"):
      assert(IntOp.width == FuOpWidth.Width + FuType.width + FuSrcWidth.Width)
      // One encoding per FU, using the widest literal of each set. `src` is don't-care for the units that take no
      // source (the legacy decoder wrote 0 there); a real member exercises the column just the same.
      val encodings: Seq[(FuType, Opcode, Opcode)] = Seq(
        (FuType.ALU, AluOp.Sltu, AluSrc.PcImm),
        (FuType.BRU, BruOp.BGEU, BruSrc.Rs1Imm),
        (FuType.MUL, MulOp.Mulhsu, AluSrc.Rs1Rs2),
        (FuType.DIV, DivOp.Remu, AluSrc.Rs1Rs2),
        (FuType.SYSU, SysuOp.EBREAK, AluSrc.Rs1Rs2)
      )
      for (unit, op, src) <- encodings do
        val window = IntOp.lit(unit, op, src)
        assert(unpack(window, IntOp.fuOp) == op.lit)
        assert(unpack(window, IntOp.fuType) == unit.lit)
        assert(unpack(window, IntOp.fuSrc) == src.lit)

      // And every member of every int set round-trips through its own column.
      for op <- AluOp.values ++ BruOp.values ++ MulOp.values ++ DivOp.values ++ SysuOp.values do
        assert(unpack(IntOp.fuOp.lit(op), IntOp.fuOp) == op.lit)
      for unit <- FuType.values do assert(unpack(IntOp.fuType.lit(unit), IntOp.fuType) == unit.lit)
      for src <- AluSrc.values ++ BruSrc.values do assert(unpack(IntOp.fuSrc.lit(src), IntOp.fuSrc) == src.lit)

    test("the memory window separates its columns"):
      assert(MemOp.width == LsuOp.width + LsuSrc.width)
      for op <- LsuOp.values; src <- LsuSrc.values do
        val window = MemOp.lit(op, src)
        assert(unpack(window, MemOp.op) == op.lit)
        assert(unpack(window, MemOp.src) == src.lit)

    test("the shared op field holds the longest window, low-anchored"):
      assert(OpField.Width == IntOp.width.max(MemOp.width))
      assert(OpField.Width == 17)
      for window <- Seq(IntOp.width, MemOp.width) do assert(window <= OpField.Width)

      // Columns are contiguous from bit 0, so a window is low-anchored and its length is exactly what it declares.
      def contiguous(columns: Seq[OpColumn]): Boolean =
        columns.head.offset == 0 && columns.zip(columns.tail).forall((low, high) => high.offset == low.end)
      assert(contiguous(Seq(IntOp.fuOp, IntOp.fuType, IntOp.fuSrc)))
      assert(contiguous(Seq(MemOp.op, MemOp.src)))
