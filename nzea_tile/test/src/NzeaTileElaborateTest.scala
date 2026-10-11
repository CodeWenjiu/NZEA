package nzea_tile

import nzea_rtl.TestFlow
import utest.*

/** The tile elaborates through the real flow as the core behind the fabric: the top carries only `clock` and `reset`,
  * and the linked design is the core, the fabric and one simulation bridge per slave.
  */
object NzeaTileElaborateTest extends TestSuite:

    private val parameter = NzeaTileParameter.default

    private lazy val sv = TestFlow.lower(
        NzeaTile.moduleName(parameter),
        cfg =>
            NzeaTile.main(
                Array(
                    "config",
                    cfg.toString,
                    "--isa",
                    parameter.config.isa.name,
                    "--defaultPc",
                    parameter.config.defaultPc.toString,
                    "--flow",
                    parameter.flow.segment,
                    "--bypass",
                    parameter.bypass.name,
                    "--stats",
                    parameter.stats.name
                ) ++ parameter.bpu.toSeq.flatMap(bpu => Seq("--bpu", bpu.name))
                    ++ Seq("--lsqDepth", parameter.lsqDepth.toString)
                    ++ parameter.icache.toSeq.flatMap(icache => Seq("--icache", icache.name))
                    ++ parameter.dcache.toSeq.flatMap(dcache => Seq("--dcache", dcache.name))
                    ++ parameter.devices.flatMap(device => Seq("--devices", upickle.default.write(device)))
            ),
        cfg => NzeaTile.main(Array("design", cfg.toString))
    )

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
            assert(NzeaTileParameter.default.devices.map(_.name) == Seq("ram", "uart16550", "finisher", "clint"))
            assert(
                NzeaTile.fabricMap(parameter.config, parameter.devices).mmioRanges.map(_.base) == Seq(
                    BigInt("10000000", 16),
                    BigInt("00100000", 16),
                    BigInt("02000000", 16)
                )
            )
