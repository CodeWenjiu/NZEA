package nzea_core.isa

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

case class IsaElaborateTestParameter(dummy: Int) extends Parameter

given upickle.default.ReadWriter[IsaElaborateTestParameter] = upickle.default.macroRW

class IsaElaborateTestLayers(parameter: IsaElaborateTestParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IsaElaborateTestIO(parameter: IsaElaborateTestParameter) extends HWBundle(parameter):
    val addr = Flipped(UInt(32))
    val csr_type = Aligned(UInt(CsrType.width))
    val csr_addr = Aligned(UInt(12))
    val csr_valid = Aligned(Bool())

class IsaElaborateTestProbe(parameter: IsaElaborateTestParameter)
    extends DVBundle[IsaElaborateTestParameter, IsaElaborateTestLayers](parameter)

/** Exercises the hardware side of the ISA-level tables: the CSR address <-> type mapping used by decode and by the
  * commit trace.
  */
@generator
object IsaElaborateTestModule
    extends Generator[IsaElaborateTestParameter, IsaElaborateTestLayers, IsaElaborateTestIO, IsaElaborateTestProbe]:
    override def moduleName(p: IsaElaborateTestParameter): String = "IsaElaborateTest"

    def architecture(parameter: IsaElaborateTestParameter) =
        val io = summon[Interface[IsaElaborateTestIO]]

        io.csr_type := CsrType.fromAddr(io.addr)
        val (csrAddr, csrValid) = CsrType.toImmValid(io.csr_type)
        io.csr_addr := csrAddr
        io.csr_valid := csrValid

/** The ISA's contracts: the shared decoder outputs and their hardware, the accepted bases, the rejected garbage, `g`
  * expansion, and the canonical string a parsed ISA spells back out.
  */
object IsaTest extends TestSuite:

    private def checkSequential(values: Seq[Encoding], expectedWidth: Int): Unit =
        assert(values.map(_.lit) == values.indices)
        assert(Encoding.width(values) == expectedWidth)

    private def ok(isa: String): Isa =
        Isa.parse(isa) match
            case Right(c)  => c
            case Left(msg) => throw new java.lang.AssertionError(s"expected valid ISA, got: $msg")

    private val module = IsaElaborateTestModule.moduleName(IsaElaborateTestParameter(0))

    private lazy val sv = TestFlow.lower(
        module,
        cfg => IsaElaborateTestModule.main(Array("config", cfg.toString, "--dummy", "0")),
        cfg => IsaElaborateTestModule.main(Array("design", cfg.toString))
    )

    private lazy val ports = TestFlow.ports(sv, module)

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

        test("module lowers with the expected port widths"):
            assert(sv.contains(s"module $module("))
            assert(ports == Map("addr" -> 32, "csr_type" -> CsrType.width, "csr_addr" -> 12, "csr_valid" -> 1))

        test("CSR address map reaches the lowered logic"):
            for addr <- Seq(
                    CsrType.MSTATUS_ADDR,
                    CsrType.MTVEC_ADDR,
                    CsrType.MSCRATCH_ADDR,
                    CsrType.MEPC_ADDR,
                    CsrType.MCAUSE_ADDR
                )
            do assert(sv.contains(s"12'h${addr.toHexString}"))
