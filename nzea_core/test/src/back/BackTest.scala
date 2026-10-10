package nzea_core.back

import nzea_core.config.CoreConfig
import nzea_core.back.int.{AluOp, BruOp, DivOp, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.{Isa, Srcs}
import nzea_rtl.stdlib.*
import utest.*

/** The field encodings the decode stage and the backends share: their field widths are load bearing numbers, so they
  * are pinned here rather than left implicit in the consumers. Each backend owns its own table and its own window in
  * the shared op field, and each op set answers with the configuration: the widths below are that answer, not a
  * literal.
  */
object BackTest extends TestSuite:

    /** What a backend reads back out of a window: the case's tag, or the payload below it.
      *
      * The layout these two helpers decode is the union's, so nothing here restates an offset: a change to the layout
      * moves the unions and this test follows.
      */
    private def payload(window: Int, union: Encoding.UnionSet[?, ?]): Int = window & ((1 << union.payloadWidth) - 1)

    private def tag(window: Int, union: Encoding.UnionSet[?, ?]): Int = window >> union.payloadWidth

    /** The int window in each shape it takes: three units without M, five with it, and a payload column that is as wide
      * as the ALU's op set — which is where the 64-bit windows grow, by the word forms.
      */
    private val rv32iConfig = CoreConfig(Isa("riscv32i"), 0x80000000L)
    private val rv32imConfig = CoreConfig(Isa("riscv32im"), 0x80000000L)
    private val rv64iConfig = CoreConfig(Isa("riscv64i"), 0x80000000L)
    private val rv64imConfig = CoreConfig(Isa("riscv64im"), 0x80000000L)

    private val isa32: Isa = rv32iConfig.isa
    private val isa64: Isa = rv64iConfig.isa
    private val isa32im: Isa = rv32imConfig.isa
    private val isa64im: Isa = rv64imConfig.isa

    private val withoutM = IntOp.set(isa32)
    private val withM = IntOp.set(rv32imConfig.isa)

    val tests = Tests:
        test("the encodings keep their field widths"):
            assert(withoutM.tagWidth == 2)
            assert(withM.tagWidth == 3)
            assert(BruOp.width == 8)
            assert(SysuOp.width == 4)
            assert(Srcs.width == 8)
            assert(AluOp.set(isa32).width == 10)
            assert(MulOp.set(isa32im).width == 4)
            assert(DivOp.set(isa32im).width == 4)
            assert(LsuOp.set(isa32).width == 8)
            assert(withM.payloadWidth == 10)
            // Without M the multiplier's and divider's sets are empty: the units are not built, so neither are their codes.
            assert(MulOp.set(isa32).variants.isEmpty && DivOp.set(isa32).variants.isEmpty)
            assert(MulOp.set(isa64).variants.isEmpty && DivOp.set(isa64).variants.isEmpty)

        test("a case is only there when the member it carries is"):
            // The unit first, then the member: the multiplier is built in both, its word form only in the 64-bit one.
            assert(IntOp.Mul(MulOp.Mul).exists(isa32im) && !IntOp.Mul(MulOp.Mul).exists(isa32))
            assert(IntOp.Mul(MulOp.Mulw).exists(isa64im) && !IntOp.Mul(MulOp.Mulw).exists(isa32im))
            assert(IntOp.Alu(AluOp.Addw).exists(isa64) && !IntOp.Alu(AluOp.Addw).exists(isa32))
            assert(
                !IntOp.set(isa32).contains(IntOp.Mul(MulOp.Mul)) && IntOp.set(isa32im).contains(IntOp.Mul(MulOp.Mul))
            )

        test("a 64-bit op set keeps the base codes and adds the word block above them"):
            assert(AluOp.set(isa64).width == 15)
            assert(MulOp.set(isa64im).width == 5)
            assert(DivOp.set(isa64im).width == 8)
            assert(LsuOp.set(isa64).width == 11)
            // The gated members are declared last, so every member before them keeps its code.
            assert(AluOp.set(isa64).variants.take(AluOp.set(isa32).variants.size) == AluOp.set(isa32).variants)
            assert(MulOp.set(isa64im).variants.take(MulOp.set(isa32im).variants.size) == MulOp.set(isa32im).variants)
            assert(DivOp.set(isa64im).variants.take(DivOp.set(isa32im).variants.size) == DivOp.set(isa32im).variants)
            assert(LsuOp.set(isa64).variants.take(LsuOp.set(isa32).variants.size) == LsuOp.set(isa32).variants)
            assert(!AluOp.set(isa32).contains(AluOp.Addw) && AluOp.set(isa64).contains(AluOp.Addw))
            assert(!MulOp.set(isa32im).contains(MulOp.Mulw) && MulOp.set(isa64im).contains(MulOp.Mulw))
            assert(!LsuOp.set(isa32).contains(LsuOp.LD) && LsuOp.set(isa64).contains(LsuOp.LD))

        test("the int window separates its payload from the case"):
            assert(withM.width == withM.payloadWidth + withM.tagWidth)
            // A case's payload sits low, its tag — where the case is in the list — above it.
            for v <- withM.cases do
                val window = withM.lit(v)
                assert(payload(window, withM) == v.payload.lit)
                assert(tag(window, withM) == withM.tagOf(v))

            // One case per unit, using the widest literal of each op set the 64-bit configuration has.
            val rv64im = IntOp.set(rv64imConfig.isa)
            val widest: Seq[(IntOp, Encoding)] = Seq(
                (IntOp.Alu(AluOp.Sraw), AluOp.Sraw),
                (IntOp.Bru(BruOp.BGEU), BruOp.BGEU),
                (IntOp.Mul(MulOp.Mulw), MulOp.Mulw),
                (IntOp.Div(DivOp.Remuw), DivOp.Remuw),
                (IntOp.Sysu(SysuOp.FENCE), SysuOp.FENCE)
            )
            for (v, op) <- widest do assert(payload(rv64im.lit(v), rv64im) == op.lit)

            // And every member of every op set survives its own case's payload column.
            for op <- AluOp.set(isa64).variants do assert(payload(rv64im.lit(IntOp.Alu(op)), rv64im) == op.lit)
            for op <- BruOp.values do assert(payload(rv64im.lit(IntOp.Bru(op)), rv64im) == op.lit)
            for op <- MulOp.set(isa64im).variants do assert(payload(rv64im.lit(IntOp.Mul(op)), rv64im) == op.lit)
            for op <- DivOp.set(isa64im).variants do assert(payload(rv64im.lit(IntOp.Div(op)), rv64im) == op.lit)
            for op <- SysuOp.values do assert(payload(rv64im.lit(IntOp.Sysu(op)), rv64im) == op.lit)

        test("the memory window carries its op and no tag"):
            val mem32 = MemOp.set(isa32)
            assert(mem32.payloadWidth == LsuOp.set(isa32).width)
            assert(mem32.tagWidth == 0)
            assert(mem32.width == LsuOp.set(isa32).width)
            for op <- LsuOp.set(isa32).variants do assert(mem32.lit(MemOp.Lsu(op)) == op.lit)
            val mem64 = MemOp.set(isa64)
            for op <- LsuOp.set(isa64).variants do assert(mem64.lit(MemOp.Lsu(op)) == op.lit)

        test("the shared op field holds the longest window, low-anchored"):
            assert(OpWindow.width(rv32iConfig) == withoutM.width.max(MemOp.set(isa32).width))
            assert(OpWindow.width(rv32imConfig) == withM.width.max(MemOp.set(isa32).width))
            assert(OpWindow.width(rv32iConfig) == 12)
            assert(OpWindow.width(rv32imConfig) == 13)
            // 64-bit windows: the payload column carries the word forms, so the field is five bits wider than its 32-bit twin.
            assert(OpWindow.width(rv64iConfig) == 17)
            assert(OpWindow.width(rv64imConfig) == 18)
            for window <- Seq(withM.width, MemOp.set(isa32).width) do assert(window <= OpWindow.width(rv32imConfig))
