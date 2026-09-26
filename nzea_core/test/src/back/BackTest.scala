package nzea_core.back

import nzea_core.back.int.{AluOp, BruOp, DivOp, FuOpWidth, FuType, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.Srcs
import nzea_rtl.stdlib.*
import utest.*

/** The field encodings the decode stage and the backends share: their field widths are load bearing numbers, so they
  * are pinned here rather than left implicit in the consumers. Each backend owns its own table and its own window in
  * the shared op field; the codes come from `Encoding.OneHot` / `Encoding.Sequential`, so there is no hand-written
  * literal left to drift (their derivation is checked by the stdlib's `EncodingTest`).
  */
object BackTest extends TestSuite:

  /** What a backend reads back out of a window: the bits the decode side packed into that column. */
  private def unpack(window: Int, column: Encoding.Column): Int =
    (window >> column.offset) & ((1 << column.width) - 1)

  val tests = Tests:
    test("the encodings keep their field widths"):
      assert(FuType.width == 3)
      assert(AluOp.width == 10)
      assert(BruOp.width == 8)
      assert(MulOp.width == 4)
      assert(DivOp.width == 4)
      assert(SysuOp.width == 4)
      assert(FuOpWidth.Width == 10)
      assert(LsuOp.width == 8)
      assert(Srcs.width == 7)

    test("the int window separates its columns"):
      assert(IntOp.width == FuOpWidth.Width + FuType.width)
      // One encoding per unit, using the widest literal of each op set.
      val encodings: Seq[(FuType, Encoding)] = Seq(
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
      assert(OpWindow.Width == IntOp.width.max(MemOp.width))
      assert(OpWindow.Width == 13)
      for window <- Seq(IntOp.width, MemOp.width) do assert(window <= OpWindow.Width)

      // Columns are contiguous from bit 0, so a window is low-anchored and its length is exactly what it declares.
      def contiguous(columns: Seq[Encoding.Column]): Boolean =
        columns.head.offset == 0 && columns.zip(columns.tail).forall((low, high) => high.offset == low.end)
      assert(contiguous(Seq(IntOp.fuOp, IntOp.fuType)))
      assert(contiguous(Seq(MemOp.op)))
