package nzea_device

import nzea_rtl.RtlFlow
import utest.*

/** The platform's inventory is the one description of what a flow builds and which windows it answers, so the core's
  * MMIO classification and the tile's device list cannot drift apart.
  */
object PlatformDevicesTest extends TestSuite:

    val tests = Tests:
        test("the platform's MMIO windows come from the flow's inventory"):
            assert(
                PlatformDevices.mmioWindows(RtlFlow.Verilator) ==
                    Seq("0x10000000:0x8", "0x100000:0x4", "0x2000000:0xc000", "0x88000000:0x8000000")
            )
            assert(
                PlatformDevices.mmioWindows(RtlFlow.Yosys) == Seq("0x10000000:0x8", "0x100000:0x4", "0x2000000:0xc000")
            )
