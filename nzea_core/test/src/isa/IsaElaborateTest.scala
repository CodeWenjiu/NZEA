package nzea_core.isa

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
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

/** Structural checks: the field widths and the CSR address map reach the lowered Verilog. */
object IsaElaborateTest extends TestSuite:

    private val module = IsaElaborateTestModule.moduleName(IsaElaborateTestParameter(0))

    private lazy val sv = TestFlow.lower(
        module,
        cfg => IsaElaborateTestModule.main(Array("config", cfg.toString, "--dummy", "0")),
        cfg => IsaElaborateTestModule.main(Array("design", cfg.toString))
    )

    private lazy val ports = TestFlow.ports(sv, module)

    val tests = Tests:
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
