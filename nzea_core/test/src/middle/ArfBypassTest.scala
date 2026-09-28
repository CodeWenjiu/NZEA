package nzea_core.middle

import nzea_core.isa.Gpr
import nzea_rtl.TestFlow
import utest.*

/** The read network's two ends now live in the file: with the writeback link a read that names the register being
  * written this cycle answers the write's own data, and without the link it answers what the file holds — that write
  * lands at the end of the cycle, so a consumer that needs it reads a cycle later. `writes` is the link's gate in the
  * lowered RTL, so its presence is the cheapest thing to pin.
  */
object ArfBypassTest extends TestSuite:

  private def lower(forward: Boolean): String =
    val parameter = ArfParameter(32, forward)
    TestFlow.lower(
      Arf.moduleName(parameter),
      cfg => Arf.main(Array("config", cfg.toString, "--width", "32", "--forward", forward.toString)),
      cfg => Arf.main(Array("design", cfg.toString))
    )

  private lazy val sv = lower(true)
  private lazy val ports = TestFlow.ports(sv, Arf.moduleName(ArfParameter(32, true)))

  val tests = Tests:
    test("the file's boundary is the write port and the two reads"):
      assert(sv.contains(s"module ${Arf.moduleName(ArfParameter(32, true))}("))
      assert(
        ports == Map(
          "clock" -> 1,
          "resetN" -> 1,
          "write_valid" -> 1,
          "write_bits_index" -> Gpr.indexWidth,
          "write_bits_data" -> 32,
          "rs1_index" -> Gpr.indexWidth,
          "rs1_data" -> 32,
          "rs2_index" -> Gpr.indexWidth,
          "rs2_data" -> 32
        )
      )

    test("the writeback link muxes the write port into the reads"):
      assert(sv.contains("commitWrites"))

    test("without the link the reads are the file's own"):
      assert(!lower(false).contains("commitWrites"))
