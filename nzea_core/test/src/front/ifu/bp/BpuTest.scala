package nzea_core.front.ifu.bp

import nzea_core.config.{BpuConfig, BpuStrategy, RasConfig}
import nzea_core.front.{Front, FrontParameter}
import nzea_core.isa.Isa
import nzea_rtl.TestFlow
import utest.*

/** The config's contract: the token round-trips with its sizes and options, a malformed one is rejected rather than
  * defaulted, and the tables exist exactly where the config says — no config builds none of them.
  */
object BpuTest extends TestSuite:

    private def front(bpu: Option[BpuConfig]): String =
        val module = Front.moduleName(FrontParameter(Isa("riscv32i"), 0x80000000L, bpu))
        TestFlow.lower(
            module,
            cfg =>
                Front.main(
                    Array(
                        "config",
                        cfg.toString,
                        "--isa",
                        "riscv32i",
                        "--defaultPc",
                        "2147483648"
                    ) ++ bpu.toSeq.flatMap(config => Seq("--bpu", config.name))
                ),
            cfg => Front.main(Array("design", cfg.toString))
        )

    private val bimodal = BpuConfig(BpuStrategy.Bimodal(128, 8), None)

    // One elaboration per parameter per JVM (the generator caches what it has already dumped), so the tests share these.
    private lazy val predicted = front(Some(bimodal))
    private lazy val off = front(None)

    val tests = Tests:
        test("the config token round-trips, sizes and options and all"):
            val configs = Seq(
                bimodal,
                BpuConfig(BpuStrategy.Bimodal(64, 4), None),
                BpuConfig(BpuStrategy.Bimodal(64, 4), Some(RasConfig(16)))
            )
            for config <- configs do assert(BpuConfig.parse(config.name) == Right(config))

        test("a malformed token is rejected, not defaulted"):
            assert(BpuConfig.parse("bimodal:7:8").isLeft)
            assert(BpuConfig.parse("bimodal:128").isLeft)
            assert(BpuConfig.parse("tage").isLeft)
            assert(BpuConfig.parse("").isLeft)
            assert(BpuConfig.parse("bimodal:64:4+ras:0").isLeft)
            assert(BpuConfig.parse("bimodal:64:4+foo").isLeft)

        test("a configured predictor builds its tables and none builds none"):
            assert(predicted.contains("module Bpu("))
            assert(predicted.contains("module Pht("))
            assert(predicted.contains("module Btb("))
            // The unit is the one place the prediction is made at every configuration; `None` is the same structure
            // without tables.
            assert(off.contains("module Bpu("))
            assert(!off.contains("module Pht("))
            assert(!off.contains("module Btb("))

        test(
            "the tables live in sync rams — the counters 2 bits, the buffer tag beside its target — and none builds none"
        ):
            assert(predicted.contains("SyncRam_dataWidth2_depth128_ports1r1w"))
            // {tag (32 - 3 index - 2) + target (32 - 2)} = 57 bits per entry, 8 entries.
            assert(predicted.contains("SyncRam_dataWidth57_depth8_ports1r1w"))
            assert(!off.contains("SyncRam_"))
