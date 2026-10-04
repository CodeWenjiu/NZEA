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
                    "--bpu",
                    parameter.bpu.name,
                    "--stats",
                    parameter.stats.name
                ) ++ parameter.devices.flatMap(device => Seq("--devices", upickle.default.write(device)))
            ),
        cfg => NzeaTile.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("the top's only ports are clock and reset — the bridges are the tile's interface here"):
            assert(TestFlow.ports(sv, "NzeaTile") == Map("clock" -> 1, "reset" -> 1))

        test("the design is the core, the fabric adapters, one device per window and the debugger"):
            val modules = Seq(
                "Core",
                "LiteBusCrossbar",
                "LiteBusROToRW",
                "LiteBusUserWiden",
                "LiteBusROSkidBuffer",
                "LiteBusRegisterSlice",
                "RamFabricSlave",
                "Uart",
                "SifiveTestFinisher",
                "Clint",
                "Debugger"
            )
            assert(modules.filterNot(module => sv.contains(s"module $module(")).isEmpty)

        test("the commit trace leaves the design on the DPI boundary"):
            assert(sv.contains("commit_trace"))

        test("the device list is the fabric's slave inventory"):
            assert(NzeaTileParameter.default.devices.map(_.name) == Seq("ram", "uart16550", "finisher", "clint"))
            assert(
                NzeaTile.fabricMap(parameter.config, parameter.devices).mmioRanges.map(_.base) == Seq(
                    BigInt("10000000", 16),
                    BigInt("00100000", 16),
                    BigInt("02000000", 16)
                )
            )
