package nzea_rtl

import mainargs.TokensReader
import nzea_rtl.bus.*
import nzea_rtl.stat.StatScope
import utest.*

/** The residence's arithmetic, naming and CLI spelling: the frame length is derived (bits against the clock, rounded up
  * — a fact of the wire, never a magic number), one name is one table, and the framework can read an entry back. The
  * behavior — every access to a hit range held for exactly one frame, in order — is pinned end to end by
  * `nzea_core/test/asm/arm_frame.S` under the remu difftest, with the pulse assertion in its header.
  *
  * The last test pins the property the shape exists for: a frame is the wire's own time, so the cycle count scales with
  * the clock and the duration does not. The line model (`DeviceTiming`) is the opposite by construction — its number is
  * bus cycles, the same count at any clock — and that split is the rule `goal/latency-calibration.md` states.
  */
object LiteBusResidencyTest extends TestSuite:

    private val table = Seq(
        FramedRange(
            range = AddrRange(base = BigInt("8A000000", 16), size = BigInt("68", 16)),
            timing = FrameTiming(bitsPerFrame = 20, bitHz = 400_000)
        )
    )

    val tests = Tests:
        test("a frame is bits against the clock, rounded up"):
            assert(FrameTiming(bitHz = 400_000, bitsPerFrame = 20).cycles(25_000_000) == 1250)
            assert(FrameTiming(bitHz = 100_000, bitsPerFrame = 20).cycles(25_000_000) == 5000)
            assert(FrameTiming(bitHz = 400_000, bitsPerFrame = 1).cycles(25_000_000) == 63)

        test("a frame keeps its duration when the clock changes"):
            val frame = FrameTiming(bitsPerFrame = 20, bitHz = 400_000)
            val at25 = frame.cycles(25_000_000)
            val at100 = frame.cycles(100_000_000)
            assert(at25 == 1250 && at100 == 5000)
            assert(BigInt(at25) * 100_000_000 == BigInt(at100) * 25_000_000)

        test("the name carries the table: one name is one body"):
            val parameter =
                LiteBusResidencyParameter(32, 32, 101, 1, 25_000_000, table, counters = false, stats = StatScope.root)
            assert(LiteBusResidency.moduleName(parameter) == "LiteBusResidency_8a000000x68c1250")

        test("the table's CLI spelling is its JSON"):
            val reader = summon[TokensReader[FramedRange]]
            val written = upickle.default.write(table.head)
            assert(reader.read(Seq(written)) == Right(table.head))
