package nzea_tile

import nzea_rtl.TestFlow
import nzea_rtl.bus.{AddrRange, SlaveKind}
import utest.*

/** The tile elaborates through the real flow as the core behind the fabric: the top carries `clock`, `reset`, and —
  * when the level above answers windows — one bus port per window, which is where it connects its devices.
  */
object NzeaTileElaborateTest extends TestSuite:

    private val parameter = NzeaTileParameter.default

    /** The default platform plus two windows the level above answers: a memory one (so the caches may refill from it)
      * and an MMIO one.
      */
    private val externalParameter = parameter.copy(
        externals = Seq(
            ExternalSpec("extMem", AddrRange(BigInt("90000000", 16), BigInt("00100000", 16)), SlaveKind.Memory, 2),
            ExternalSpec("extRegs", AddrRange(BigInt("03000000", 16), BigInt("00001000", 16)), SlaveKind.Mmio, 1)
        )
    )

    /** The `config` half of the two-step protocol, one flag per field — the same list [[NzeaTileElaborate]] spells. */
    private def configFlags(p: NzeaTileParameter): Seq[String] =
        Seq(
            "--isa",
            p.config.isa.name,
            "--defaultPc",
            p.config.defaultPc.toString,
            "--flow",
            p.flow.segment,
            "--clockHz",
            p.clockHz.toString,
            "--bypass",
            p.bypass.name,
            "--stats",
            p.stats.name
        ) ++ p.bpu.toSeq.flatMap(bpu => Seq("--bpu", bpu.name))
            ++ Seq("--lsqDepth", p.lsqDepth.toString)
            ++ p.icache.toSeq.flatMap(icache => Seq("--icache", icache.name))
            ++ p.dcache.toSeq.flatMap(dcache => Seq("--dcache", dcache.name))
            ++ p.externals.flatMap(external => Seq("--externals", upickle.default.write(external)))

    private def lower(p: NzeaTileParameter): String = TestFlow.lower(
        NzeaTile.moduleName(p),
        cfg => NzeaTile.main(Array("config", cfg.toString) ++ configFlags(p)),
        cfg => NzeaTile.main(Array("design", cfg.toString))
    )

    private lazy val sv = lower(parameter)

    private lazy val externalSv = lower(externalParameter)

    val tests = Tests:
        test("the top's only ports are clock and reset — the bridges are the tile's interface here"):
            assert(TestFlow.ports(sv, "NzeaTile") == Map("clock" -> 1, "reset" -> 1))

        test(
            "the design is the core, the queue, the caches, the fabric adapters, one device per window and the debugger"
        ):
            val exact = Seq(
                "Core",
                "Back",
                "Completion",
                "CompletionQueue",
                "ICache",
                "DCache",
                "LiteBusCrossbar",
                "LiteBusUserWiden",
                "RamFabricSlave",
                "Uart",
                "SifiveTestFinisher",
                "Clint",
                "Debugger"
            )
            // Parameterized bodies carry their shape in the name, so the check is on the prefix.
            val prefixed = Seq("LiteBusTiming_", "CacheStorage_", "CacheRefill_", "CacheBank_")
            assert(exact.filterNot(module => sv.contains(s"module $module(")).isEmpty)
            assert(prefixed.filterNot(prefix => sv.contains(s"module $prefix")).isEmpty)

        // The boundary buffers are the core's own datapath, not devices: their registers are inlined in the core and
        // named after the boundary, and the policy shows in what they hold (a skid has a tail, a one-entry slice has
        // not).
        test("the core's boundary buffers are inlined, named, and shaped by their policy"):
            assert(sv.contains("ibusBufferHeadValid") && sv.contains("ibusBufferTailValid"))
            assert(sv.contains("dbusBufferValid") && !sv.contains("dbusBufferTailValid"))

        test("the commit trace leaves the design on the DPI boundary"):
            assert(sv.contains("commit_trace"))

        // The predictor's training is the default config's: the core builds a predictor by default, so the sideband
        // from the back to the front — and the registers behind it — are there.
        test("the predictor's training sideband is built with the default config"):
            assert(sv.contains("bp_update_valid"))

        test("the device list is the fabric's slave inventory"):
            assert(NzeaTileParameter.default.built.map(_.name) == Seq("ram", "uart16550", "finisher", "clint"))
            assert(
                NzeaTile
                    .fabricMap(parameter)
                    .mmioRanges
                    .map(_.base) == Seq(
                    BigInt("10000000", 16),
                    BigInt("00100000", 16),
                    BigInt("02000000", 16)
                )
            )

        test("an external window leaves the tile as its own bus port, the fabric's shape, the tile as master"):
            val ports = TestFlow.ports(externalSv, "NzeaTile")
            // The request side leaves the tile and the response side enters it — the polarity a device answers on.
            assert(ports.get("extMem_req_valid").contains(1))
            assert(ports.get("extMem_req_bits_addr").contains(32))
            assert(ports.get("extMem_req_bits_wdata").contains(32))
            assert(ports.get("extMem_req_bits_wstrb").contains(4))
            assert(ports.get("extMem_resp_valid").contains(1))
            assert(ports.get("extMem_resp_ready").contains(1))
            assert(ports.keys.exists(_.startsWith("extRegs_")))

        test("an external window is a window on the map, and its kind decides what it joins"):
            val map = NzeaTile.fabricMap(externalParameter)
            assert(map.slaves.map(_.name) == Seq("ram", "uart16550", "finisher", "clint", "extMem", "extRegs"))
            // A memory window joins what a cache may refill from (the tile's own filter), an MMIO one the trace's windows.
            assert(map.slaves.filter(_.kind == SlaveKind.Memory).map(_.name) == Seq("ram", "extMem"))
            assert(map.mmioRanges.map(_.base).contains(BigInt("03000000", 16)))
            assert(map.slaves.find(_.name == "extMem").exists(_.outstanding == 2))

        test("an external window that clashes with a device's is refused, not quietly decoded"):
            // Shadowing is by name: a window named after nothing the flow has still has to keep off the built windows.
            intercept[IllegalArgumentException]:
                NzeaTile.fabricMap(
                    parameter.copy(
                        externals = Seq(
                            ExternalSpec(
                                "clash",
                                AddrRange(BigInt("80000000", 16), BigInt("00001000", 16)),
                                SlaveKind.Memory,
                                1
                            )
                        )
                    )
                )
