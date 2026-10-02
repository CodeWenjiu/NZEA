package nzea_core.stats

import nzea_core.config.{BpuLevel, BypassLevel}
import nzea_core.{Core, CoreParameter}
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.isa.Isa
import nzea_core.middle.{Middle, MiddleParameter}
import nzea_rtl.{RtlFlow, StatBox, StatSchema, StatScope, TestFlow}
import utest.*

/** The contract between a counter declared at its call site and what a dump emits from it: the registered set is the
  * schema, and every counter's box is instantiated by the module that declared it.
  *
  * The registration is process-global and a lowering resets it, so each run is captured into a [[Run]] the moment it
  * finishes.
  */
object CountersTest extends TestSuite:

    private final case class Run(sv: String, schema: StatSchema)

    private def run(lower: => String): Run =
        val sv = lower
        Run(sv, nzea_rtl.StatRegistry.schema)

    private lazy val middleRun = run(
        TestFlow.lower(
            middle,
            cfg =>
                Middle.main(
                    Array(
                        "config",
                        cfg.toString,
                        "--isa",
                        "riscv32i",
                        "--defaultPc",
                        "2147483648",
                        "--flow",
                        "verilator",
                        "--bypass",
                        "commit",
                        "--stats",
                        "middle"
                    )
                ),
            cfg => Middle.main(Array("design", cfg.toString))
        )
    )

    private lazy val intRun = run(
        TestFlow.lower(
            intPath,
            cfg =>
                IntPath.main(
                    Array(
                        "config",
                        cfg.toString,
                        "--isa",
                        "riscv32i",
                        "--defaultPc",
                        "2147483648",
                        "--flow",
                        "verilator",
                        "--bpu",
                        "off",
                        "--stats",
                        "int"
                    )
                ),
            cfg => IntPath.main(Array("design", cfg.toString))
        )
    )

    private lazy val coreRun = run(
        TestFlow.lower(
            core,
            cfg =>
                Core.main(
                    Array(
                        "config",
                        cfg.toString,
                        "--isa",
                        "riscv32i",
                        "--defaultPc",
                        "2147483648",
                        "--flow",
                        "verilator",
                        "--bypass",
                        "commit",
                        "--bpu",
                        "off",
                        "--mmioRanges",
                        "0x10000000:0x1000",
                        "--stats",
                        "core"
                    )
                ),
            cfg => Core.main(Array("design", cfg.toString))
        )
    )

    private lazy val middle =
        Middle.moduleName(
            MiddleParameter(
                Isa("riscv32i"),
                0x80000000L,
                RtlFlow.Verilator,
                BypassLevel.Commit,
                StatScope(Seq("middle"))
            )
        )

    private lazy val intPath =
        IntPath.moduleName(
            IntPathParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Verilator, StatScope(Seq("int")), BpuLevel.Off)
        )

    private lazy val core =
        Core.moduleName(
            CoreParameter(
                Isa("riscv32i"),
                0x80000000L,
                RtlFlow.Verilator,
                Seq("0x10000000:0x1000"),
                BypassLevel.Commit,
                BpuLevel.Off,
                StatScope(Seq("core"))
            )
        )

    val tests = Tests:
        test("the middle's stages declare its throughput counters and the rule over them"):
            assert(
                middleRun.schema.counters.map(c => (c.name, c.source)) ==
                    Seq(
                        "stat_middle_operand_wait" -> "middle",
                        "stat_middle_slot_busy" -> "middle",
                        "stat_middle_cycle" -> "middle",
                        "stat_middle_inst_commit" -> "middle",
                        "stat_middle_flush" -> "middle"
                    )
            )
            assert(middleRun.schema.derived.map(_.name) == Seq("ipc"))
            assert(
                middleRun.schema.derived.map(_.expr) == Seq("stat_middle_inst_commit / stat_middle_cycle")
            )
            assert(middleRun.schema.regions.map(_.name) == Seq("throughput"))
            middleRun.schema.validate()

        test("the int path declares its branch counters and the rule over them"):
            assert(
                intRun.schema.counters
                    .map(_.name) == Seq("stat_int_bp_branch", "stat_int_bp_taken", "stat_int_bp_mispred")
            )
            assert(intRun.schema.counters.map(_.source).distinct == Seq("int"))
            assert(intRun.schema.derived.map(_.name) == Seq("taken_rate", "accuracy"))
            assert(
                intRun.schema.derived.map(_.expr) ==
                    Seq(
                        "stat_int_bp_taken / stat_int_bp_branch * 100",
                        "(stat_int_bp_branch - stat_int_bp_mispred) / stat_int_bp_branch * 100"
                    )
            )
            assert(intRun.schema.regions.map(_.name) == Seq("bp"))
            intRun.schema.validate()

        test("the core declares the instruction port's bandwidth counters"):
            val fetch = coreRun.schema.counters.filter(_.region.name == "fetch")
            assert(
                fetch.map(_.name) ==
                    Seq("stat_core_ibus_req", "stat_core_ibus_resp", "stat_core_ibus_stall", "stat_core_fetch_fire")
            )
            assert(fetch.map(_.source).distinct == Seq("core"))
            coreRun.schema.validate()

        test("every counter is box-width, and its box is instantiated by the module that declared it"):
            for r <- Seq(middleRun, intRun, coreRun) do
                for c <- r.schema.counters do
                    require(c.width == StatBox.Width, s"${c.name} is ${c.width} bits wide, not ${StatBox.Width}")
                    val instance = raw"(?m)^\s*${c.name}\s+\w+\s*\(".r
                    require(instance.findFirstIn(r.sv).isDefined, s"${c.name}'s box is not instantiated")
