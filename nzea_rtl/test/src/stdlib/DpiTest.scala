package nzea_rtl.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import utest.*

case class DpiTestParameter(width: Int) extends Parameter:
    require(width >= 1, s"width must be positive, got $width")

given upickle.default.ReadWriter[DpiTestParameter] = upickle.default.macroRW

class DpiTestLayers(parameter: DpiTestParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The three functions remu's C side provides, with the argument widths that ABI declares. */
class DpiTestIO(parameter: DpiTestParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val addr = Flipped(UInt(32))
    val enable = Flipped(Bool())
    val wdata = Flipped(UInt(32))
    val wstrb = Flipped(UInt(32))
    val csr_valid = Flipped(Bool())
    val csr_addr = Flipped(UInt(32))
    val csr_data = Flipped(UInt(32))
    val gpr_addr = Flipped(UInt(32))
    val gpr_data = Flipped(UInt(32))
    val is_mmio = Flipped(Bool())
    val rdata = Aligned(UInt(parameter.width))

class DpiTestProbe(parameter: DpiTestParameter) extends DVBundle[DpiTestParameter, DpiTestLayers](parameter)

/** Calls a combinational read and the two clocked side-effecting functions. */
@generator
object DpiTestModule extends Generator[DpiTestParameter, DpiTestLayers, DpiTestIO, DpiTestProbe]:
    override def moduleName(p: DpiTestParameter): String = s"DpiTest_w${p.width}"

    def architecture(parameter: DpiTestParameter) =
        val io = summon[Interface[DpiTestIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        io.rdata := dpiUnclockedCall(
            "bus_read",
            UInt(parameter.width),
            Some(Seq("addr")),
            Some("rdata")
        )(io.enable, io.addr)

        dpiClockedCall("bus_write", Some(Seq("addr", "wdata", "wstrb")))(
            io.clock,
            io.enable,
            io.addr,
            io.wdata,
            io.wstrb
        )

        dpiClockedCall(
            "commit_trace",
            Some(Seq("next_pc", "csr_valid", "csr_addr", "csr_data", "gpr_addr", "gpr_data", "is_mmio"))
        )(io.clock, io.enable, io.addr, io.csr_valid, io.csr_addr, io.csr_data, io.gpr_addr, io.gpr_data, io.is_mmio)

/** Structural checks: the DPI functions reach the Verilog as `import "DPI-C"` declarations with the shape remu links
  * against. Every assertion is made on whitespace-normalized Verilog, and the expected signatures are the ones in the
  * legacy design's generated `VTop_core_riscv32i__Dpi.h`.
  */
object DpiTest extends TestSuite:

    private val module = DpiTestModule.moduleName(DpiTestParameter(32))

    private lazy val sv = TestFlow.lower(
        module,
        cfg => DpiTestModule.main(Array("config", cfg.toString, "--width", "32")),
        cfg => DpiTestModule.main(Array("design", cfg.toString))
    )

    /** The Verilog with location comments stripped and whitespace collapsed, so assertions can span generated lines. */
    private lazy val flat = sv.replaceAll("//[^\n]*", "").replaceAll("\\s+", " ")

    val tests = Tests:
        test("bus_read is imported as a void function with an output pointer"):
            assert(
                flat.contains("""import "DPI-C" context function void bus_read( input int addr, output int rdata );""")
            )

        test("bus_write and commit_trace are imported as void functions"):
            assert(flat.contains("""import "DPI-C" context function void bus_write( input int addr, wdata, wstrb );"""))
            assert(
                flat.contains(
                    """import "DPI-C" context function void commit_trace( input int next_pc, input bit csr_valid, """ +
                        """input int csr_addr, csr_data, gpr_addr, gpr_data, input bit is_mmio );"""
                )
            )

        test("bus_read is a guarded combinational call with an undefined disabled value"):
            assert(flat.contains("always @(*) begin if (enable) begin bus_read(addr,"))
            assert(flat.contains("32'bx"))

        test("side-effecting calls sit in one clocked block guarded by the enable operand"):
            assert(flat.contains("always @(posedge clock) begin if (enable) begin bus_write("))
            assert(flat.contains("commit_trace(addr, csr_valid, csr_addr, csr_data, gpr_addr, gpr_data, is_mmio)"))
