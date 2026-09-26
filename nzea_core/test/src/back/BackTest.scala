package nzea_core.back

import nzea_core.back.int.{AluOp, BruOp, DivOp, FuOpWidth, FuType, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.Srcs
import nzea_rtl.stdlib.*
import utest.*

/** The opcode tables the decode stage and the backends share: widths and (one-hot) encodings are load bearing, so they
  * are pinned here rather than left implicit in the consumers. Each backend owns its own table and its own window in the
  * shared op field.
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
    test("the int path's unit select keeps its encoding"):
      checkSequential(FuType.values.toSeq, 3)

    test("the int path's op sets keep their encodings"):
      checkOneHot(AluOp.values.toSeq, 10)
      checkOneHot(BruOp.values.toSeq, 8)
      checkOneHot(MulOp.values.toSeq, 4)
      checkOneHot(DivOp.values.toSeq, 4)
      checkSequential(SysuOp.values.toSeq, 4)
      assert(FuOpWidth.Width == 10)

    test("the memory path's op set keeps its encoding"):
      checkOneHot(LsuOp.values.toSeq, 8)

    test("the operand forms keep their one-hot encoding"):
      // One form per source assignment; the field selects a slot's source with `mux1H`, which is only correct while the
      // literals are one-hot and in this order.
      checkOneHot(Srcs.values.toSeq, 7)
      assert(Srcs.NoOperand.lit == 1)

    test("the int window separates its columns"):
      assert(IntOp.width == FuOpWidth.Width + FuType.width)
      // One encoding per unit, using the widest literal of each op set.
      val encodings: Seq[(FuType, Opcode)] = Seq(
        (FuType.ALU, AluOp.Sltu),
        (FuType.BRU, BruOp.BGEU),
        (FuType.MUL, MulOp.Mulhsu),
        (FuType.DIV, DivOp.Remu),
        (FuType.SYSU, SysuOp.FENCE)
      )
      for (unit, op) <- encodings do
        val window = IntOp.lit(unit, op)
        assert(unpack(window, IntOp.fuOp) == op.lit)
        assert(unpack(window, IntOp.fuType) == unit.lit)

      // And every member of every int set round-trips through its own column.
      for op <- AluOp.values ++ BruOp.values ++ MulOp.values ++ DivOp.values ++ SysuOp.values do
        assert(unpack(IntOp.fuOp.lit(op), IntOp.fuOp) == op.lit)
      for unit <- FuType.values do assert(unpack(IntOp.fuType.lit(unit), IntOp.fuType) == unit.lit)

    test("the memory window separates its columns"):
      assert(MemOp.width == LsuOp.width)
      for op <- LsuOp.values do assert(unpack(MemOp.lit(op), MemOp.op) == op.lit)

    test("the shared op field holds the longest window, low-anchored"):
      assert(OpField.Width == IntOp.width.max(MemOp.width))
      assert(OpField.Width == 13)
      for window <- Seq(IntOp.width, MemOp.width) do assert(window <= OpField.Width)

      // Columns are contiguous from bit 0, so a window is low-anchored and its length is exactly what it declares.
      def contiguous(columns: Seq[OpColumn]): Boolean =
        columns.head.offset == 0 && columns.zip(columns.tail).forall((low, high) => high.offset == low.end)
      assert(contiguous(Seq(IntOp.fuOp, IntOp.fuType)))
      assert(contiguous(Seq(MemOp.op)))
