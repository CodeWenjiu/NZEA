package nzea_core.back

import nzea_core.back.int.{AluOp, BruOp, DivOp, IntOp, MulOp, SysuOp}
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

  /** What a backend reads back out of a window: the case's tag, or the payload below it.
    *
    * The layout these two helpers decode is the union's, so nothing here restates an offset: a change to the layout
    * moves the unions and this test follows.
    */
  private def payload(window: Int, union: Encoding.UnionSet[?]): Int = window & ((1 << union.payloadWidth) - 1)

  private def tag(window: Int, union: Encoding.UnionSet[?]): Int = window >> union.payloadWidth

  val tests = Tests:
    test("the encodings keep their field widths"):
      assert(IntOp.tagWidth == 3)
      assert(AluOp.width == 10)
      assert(BruOp.width == 8)
      assert(MulOp.width == 4)
      assert(DivOp.width == 4)
      assert(SysuOp.width == 4)
      assert(IntOp.payloadWidth == 10)
      assert(LsuOp.width == 8)
      assert(Srcs.width == 7)

    test("the int window separates its payload from the case"):
      assert(IntOp.width == IntOp.payloadWidth + IntOp.tagWidth)
      // Every case puts its payload low and its ordinate above it.
      for v <- IntOp.cases do
        val window = IntOp.lit(v)
        assert(payload(window, IntOp) == v.payload.lit)
        assert(tag(window, IntOp) == v.ordinal)

      // One case per unit, using the widest literal of each op set.
      val widest: Seq[(IntOp, Encoding)] = Seq(
        (IntOp.Alu(AluOp.Sltu), AluOp.Sltu),
        (IntOp.Bru(BruOp.BGEU), BruOp.BGEU),
        (IntOp.Mul(MulOp.Mulhsu), MulOp.Mulhsu),
        (IntOp.Div(DivOp.Remu), DivOp.Remu),
        (IntOp.Sysu(SysuOp.FENCE), SysuOp.FENCE)
      )
      for (v, op) <- widest do assert(payload(IntOp.lit(v), IntOp) == op.lit)

      // And every member of every op set survives its own case's payload column.
      for op <- AluOp.values do assert(payload(IntOp.lit(IntOp.Alu(op)), IntOp) == op.lit)
      for op <- BruOp.values do assert(payload(IntOp.lit(IntOp.Bru(op)), IntOp) == op.lit)
      for op <- MulOp.values do assert(payload(IntOp.lit(IntOp.Mul(op)), IntOp) == op.lit)
      for op <- DivOp.values do assert(payload(IntOp.lit(IntOp.Div(op)), IntOp) == op.lit)
      for op <- SysuOp.values do assert(payload(IntOp.lit(IntOp.Sysu(op)), IntOp) == op.lit)

    test("the memory window carries its op and no tag"):
      assert(MemOp.payloadWidth == LsuOp.width)
      assert(MemOp.tagWidth == 0)
      assert(MemOp.width == LsuOp.width)
      for op <- LsuOp.values do assert(MemOp.lit(MemOp.Lsu(op)) == op.lit)

    test("the shared op field holds the longest window, low-anchored"):
      assert(OpWindow.Width == IntOp.width.max(MemOp.width))
      assert(OpWindow.Width == 13)
      for window <- Seq(IntOp.width, MemOp.width) do assert(window <= OpWindow.Width)
