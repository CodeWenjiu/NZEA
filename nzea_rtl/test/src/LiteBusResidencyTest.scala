package nzea_rtl

import mainargs.TokensReader
import nzea_rtl.bus.*
import nzea_rtl.stat.StatScope
import utest.*

/** The residence's arithmetic, naming and CLI spelling: the frame length is derived (bits against the clock, rounded up
  * — a fact of the wire, never a magic number), one name is one table, and the framework can read an entry back. The
  * behavior — every access to a hit range held for exactly one frame, in order — is pinned end to end by
  * `nzea_core/test/asm/arm_frame.S` under the remu difftest, with the pulse assertion in its header.
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

        test("the name carries the table: one name is one body"):
            val parameter =
                LiteBusResidencyParameter(32, 32, 101, 1, 25_000_000, table, counters = false, stats = StatScope.root)
            assert(LiteBusResidency.moduleName(parameter) == "LiteBusResidency_8a000000x68c1250")

        test("the table's CLI spelling is its JSON"):
            val reader = summon[TokensReader[FramedRange]]
            val written = upickle.default.write(table.head)
            assert(reader.read(Seq(written)) == Right(table.head))
