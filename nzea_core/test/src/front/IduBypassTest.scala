package nzea_core.front

import nzea_config.core.{BypassConfig, Isa}
import nzea_core.front.idu.{Idu, IduParameter}
import nzea_rtl.TestFlow
import utest.*

/** The read network's two ends: with the writeback link the commit port's write is muxed into the reads, and without it
  * the read is the register file's own — the dump the tree had before the link existed. `commitWrites` is the link's
  * logic in the lowered RTL (the comparison and the valid gate), so its presence is the cheapest thing to pin.
  */
object IduBypassTest extends TestSuite:

  private def lower(bypass: BypassConfig): String =
    val module = Idu.moduleName(IduParameter(Isa("riscv32i"), 0x80000000L, bypass))
    TestFlow.lower(
      module,
      cfg =>
        Idu.main(
          Array("config", cfg.toString, "--isa", "riscv32i", "--defaultPc", "2147483648", "--bypass", bypass.name)
        ),
      cfg => Idu.main(Array("design", cfg.toString))
    )

  val tests = Tests:
    test("the writeback link muxes the commit port into the reads"):
      val sv = lower(BypassConfig.writeback)
      assert(sv.contains("commitWrites"))

    test("without the link nothing is emitted for it"):
      val sv = lower(BypassConfig.none)
      assert(!sv.contains("commitWrites"))
