package nzea_core.middle

import nzea_core.isa.Gpr
import nzea_rtl.TestFlow
import utest.*

/** The file's contract: the write port and the two reads, and nothing between them — every producer younger than the
  * file is the forwarding network's to offer, so a read answers what the file holds and the write lands at the end of
  * its cycle.
  */
object ArfTest extends TestSuite:

    private val parameter = ArfParameter(32)

    private lazy val sv = TestFlow.lower(
        Arf.moduleName(parameter),
        cfg => Arf.main(Array("config", cfg.toString, "--width", "32")),
        cfg => Arf.main(Array("design", cfg.toString))
    )

    private lazy val ports = TestFlow.ports(sv, Arf.moduleName(parameter))

    val tests = Tests:
        test("the file's boundary is the write port and the two reads"):
            assert(sv.contains(s"module ${Arf.moduleName(parameter)}("))
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

        test("the reads are the file's own muxes"):
            // A forwarding mux would make the read select the write port's data; the read muxes only select registers.
            assert(sv.contains("casez_tmp = xregs_"))
            assert(!sv.contains("casez_tmp = write_bits_data"))
