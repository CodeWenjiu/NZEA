package nzea_core.front.ifu.bp

import nzea_core.BpuLevel
import nzea_core.front.{Front, FrontParameter}
import nzea_core.isa.Isa
import nzea_rtl.TestFlow
import utest.*

/** The level's contract: the token round-trips with its sizes, a malformed one is rejected rather than defaulted, and
  * the tables exist exactly where the level says — `Off` builds none of them.
  */
object BpuTest extends TestSuite:

  private def front(level: BpuLevel): String =
    val module = Front.moduleName(FrontParameter(Isa("riscv32i"), 0x80000000L, level))
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
            "2147483648",
            "--bpu",
            level.name
          )
        ),
      cfg => Front.main(Array("design", cfg.toString))
    )

  // One elaboration per parameter per JVM (the generator caches what it has already dumped), so the tests share these.
  private lazy val predicted = front(BpuLevel.Bimodal(128, 8))
  private lazy val off = front(BpuLevel.Off)

  val tests = Tests:
    test("the level token round-trips, sizes and all"):
      for level <- Seq[BpuLevel](BpuLevel.Off, BpuLevel.Bimodal(128, 8), BpuLevel.Bimodal(64, 4)) do
        assert(BpuLevel.parse(level.name) == Right(level))
      assert(BpuLevel.Off.predicted == false)
      assert(BpuLevel.Bimodal(128, 8).predicted)

    test("a malformed token is rejected, not defaulted"):
      assert(BpuLevel.parse("bimodal:7:8").isLeft)
      assert(BpuLevel.parse("bimodal:128").isLeft)
      assert(BpuLevel.parse("tage").isLeft)
      assert(BpuLevel.parse("").isLeft)

    test("the predicted level builds its tables and Off builds none"):
      assert(predicted.contains("module Bpu("))
      assert(predicted.contains("module Pht("))
      assert(predicted.contains("module Btb("))
      // The unit is the one place the prediction is made at every level; `Off` is the same structure without tables.
      assert(off.contains("module Bpu("))
      assert(!off.contains("module Pht("))
      assert(!off.contains("module Btb("))

    test("the tables live in sync rams — the counters 2 bits, the buffer tag beside its target — and Off builds none"):
      assert(predicted.contains("SyncRam_dataWidth2_depth128_ports1r1w"))
      // {tag (32 - 3 index - 2) + target (32 - 2)} = 57 bits per entry, 8 entries.
      assert(predicted.contains("SyncRam_dataWidth57_depth8_ports1r1w"))
      assert(!off.contains("SyncRam_"))
