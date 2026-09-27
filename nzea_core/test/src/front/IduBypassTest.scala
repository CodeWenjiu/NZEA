package nzea_core.front

import nzea_core.BypassConfig
import nzea_core.front.idu.{Idu, IduParameter}
import nzea_core.isa.Isa
import nzea_rtl.TestFlow
import utest.*

/** The read network's two ends: with the writeback link the commit port's write is muxed into the reads, and without it
  * the read is the register file's own — the dump the tree had before the link existed. `commitWrites` and
  * `executeWrites` are the links' logic in the lowered RTL (the comparison and the valid gate), so their presence is
  * the cheapest thing to pin. An execute link needs the commit port's too, so it brings both.
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

    test("the execute link muxes the execute stage into the reads"):
      val sv = lower(BypassConfig.execute)
      assert(sv.contains("executeWrites"))
      assert(sv.contains("commitWrites"))

    test("without the link nothing is emitted for it"):
      val sv = lower(BypassConfig.none)
      assert(!sv.contains("commitWrites"))
      assert(!sv.contains("executeWrites"))
