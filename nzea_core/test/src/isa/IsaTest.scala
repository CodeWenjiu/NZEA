package nzea_core.isa

import nzea_rtl.stdlib.*
import utest.*

/** The ISA-level decoder outputs everyone shares: the backend dispatch and the CSR map. */
object IsaTest extends TestSuite:

    private def checkSequential(values: Seq[Encoding], expectedWidth: Int): Unit =
        assert(values.map(_.lit) == values.indices)
        assert(Encoding.width(values) == expectedWidth)

    val tests = Tests:
        test("backend dispatch keeps a stable encoding"):
            checkSequential(Backend.values.toSeq, 1)

        test("CSR types and addresses match the machine-mode map"):
            checkSequential(CsrType.values.toSeq, 3)
            assert(CsrType.MSTATUS_ADDR == 0x300)
            assert(CsrType.MTVEC_ADDR == 0x305)
            assert(CsrType.MSCRATCH_ADDR == 0x340)
            assert(CsrType.MEPC_ADDR == 0x341)
            assert(CsrType.MCAUSE_ADDR == 0x342)
