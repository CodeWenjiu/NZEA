package nzea_rtl

import mainargs.TokensReader
import nzea_rtl.bus.*
import utest.*

/** The address map is the single source the decode and the port lookup read, and it rejects an ambiguous map. */
object AddressMapTest extends TestSuite:

    private val map = AddressMap(
        masters = Seq(MasterPort("ibus", userWidth = 20, idWidth = 1), MasterPort("dbus", userWidth = 24, idWidth = 1)),
        slaves = Seq(
            SlavePort(
                "ram",
                AddrRange(BigInt("80000000", 16), BigInt("08000000", 16)),
                SlaveKind.Memory,
                outstanding = 4
            ),
            SlavePort("uart", AddrRange(BigInt("10000000", 16), BigInt("8", 16)), SlaveKind.Mmio),
            SlavePort("clint", AddrRange(BigInt("02000000", 16), BigInt("c000", 16)), SlaveKind.Mmio)
        )
    )

    private val reader = summon[TokensReader.Simple[AddressMap]]

    val tests = Tests:
        test("a name is the handle: index and port come back, order is the map's"):
            assert(map.master("ibus").userWidth == 20)
            assert(map.masterIndex("dbus") == 1)
            assert(map.slaveIndex("ram") == 0)
            assert(map.slave("uart").range.size == 8)
            assert(map.slave("ram").outstanding == 4)

        test("the fabric's widths are the widest a master speaks"):
            assert(map.userWidth == 24)
            assert(map.idWidth == 1)
            assert(map.ranges.length == 3)

        test("MMIO is declared, not derived from an address"):
            assert(map.mmioRanges == Seq(map.slave("uart").range, map.slave("clint").range))

        test("an unknown name fails loudly, naming what is there"):
            val e = intercept[NoSuchElementException](map.slave("rom"))
            assert(e.getMessage.contains("rom"))
            assert(e.getMessage.contains("ram"))

        test("windows must not overlap; the duplicate is named"):
            val e = intercept[IllegalArgumentException]:
                AddressMap(
                    masters = Seq(MasterPort("m", 8, 1)),
                    slaves = Seq(
                        SlavePort("a", AddrRange(0x1000, 0x1000), SlaveKind.Memory),
                        SlavePort("b", AddrRange(0x1800, 0x1000), SlaveKind.Memory)
                    )
                )
            assert(e.getMessage.contains("a") && e.getMessage.contains("b"))

        test("a bare range answers membership"):
            val r = AddrRange(0x10, 0x10)
            assert(r.contains(0x10) && r.contains(0x1f))
            assert(!r.contains(0x0f) && !r.contains(0x20))

        test("the map round-trips through JSON, the flag's one spelling"):
            val text = upickle.default.write(map)
            assert(upickle.default.read[AddressMap](text) == map)
            assert(reader.read(Seq(text)) == Right(map))
