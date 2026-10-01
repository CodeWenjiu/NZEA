package nzea_core.isa

import utest.*

/** ISA contract: accepted bases, rejected garbage, `g` expansion, and the canonical string a parsed ISA spells back
  * out.
  */
object IsaParseTest extends TestSuite:

    private def ok(isa: String): Isa =
        Isa.parse(isa) match
            case Right(c)  => c
            case Left(msg) => throw new java.lang.AssertionError(s"expected valid ISA, got: $msg")

    val tests = Tests:
        test("riscv32im"):
            val c = ok("riscv32im")
            assert(c.xlen == Xlen.Rv32)
            assert(c.hasM)

        test("riscv32i base only"):
            val c = ok("riscv32i")
            assert(c.extensions == Set(Ext.I))

        test("the width enum answers the widths the design derives"):
            assert(Xlen.Rv32.bits == 32 && Xlen.Rv32.lanes == 4)
            assert(Xlen.Rv64.bits == 64 && Xlen.Rv64.lanes == 8)

        test("an ISA spells back out canonically, however it was written"):
            assert(ok("rv32im").name == "riscv32im")
            assert(ok("RISCV64IM").name == "riscv64im")
            // Extensions come out in declaration order, not the order the string happened to list them in.
            assert(ok("riscv32ami").name == "riscv32ima")

        test("named extensions are rejected (not supported yet)"):
            assert(Isa.parse("riscv32im_zve32x_zvl128b").isLeft)
            assert(Isa.parse("riscv32im_wjcus0").isLeft)

        test("reject garbage base extension letters"):
            assert(Isa.parse("riscv32sdksa").isLeft)
            assert(Isa.parse("riscv32imk").isLeft)

        test("reject unrecognized base prefix"):
            assert(Isa.parse("garbage").isLeft)

        test("reject base without i or e"):
            assert(Isa.parse("riscv32m").isLeft)

        test("g expands to imafd"):
            val c = ok("riscv32g")
            assert(c.extensions == Set(Ext.I, Ext.M, Ext.A, Ext.F, Ext.D))
            assert(c.name == "riscv32imafd")
