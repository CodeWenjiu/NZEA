package nzea_rtl.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import utest.*

/** The design the tests lower: one sync ram, driven from the design's own ports. */
case class SyncRamTestParameter(width: Int, depth: Int) extends Parameter:

    val ram: SyncRamParameter =
        SyncRamParameter(width, depth, SyncRamPorts.OneR1W, SyncRamReadUnderWrite.ReadFirst)

given upickle.default.ReadWriter[SyncRamTestParameter] = upickle.default.macroRW

class SyncRamTestLayers(parameter: SyncRamTestParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class SyncRamTestIO(parameter: SyncRamTestParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val we = Flipped(Bool())
    val waddr = Flipped(UInt(parameter.ram.addressWidth))
    val wdata = Flipped(UInt(parameter.ram.width))
    val re = Flipped(Bool())
    val raddr = Flipped(UInt(parameter.ram.addressWidth))
    val rdata = Aligned(UInt(parameter.ram.width))

class SyncRamTestProbe(parameter: SyncRamTestParameter)
    extends DVBundle[SyncRamTestParameter, SyncRamTestLayers](parameter)

@generator
object SyncRamTestDesign extends Generator[SyncRamTestParameter, SyncRamTestLayers, SyncRamTestIO, SyncRamTestProbe]:
    override def moduleName(parameter: SyncRamTestParameter): String = "SyncRamTestDesign"

    def architecture(parameter: SyncRamTestParameter) =
        val io = summon[Interface[SyncRamTestIO]]
        given ClockScope = ClockScope.posedge(io.clock)

        val ram = SyncRam.instantiate(parameter.ram)
        ram.io.clock := io.clock
        ram.io.wEn := io.we
        ram.io.wAddr := io.waddr
        ram.io.wData := io.wdata
        ram.io.rEn := io.re
        ram.io.rAddr := io.raddr
        io.rdata := ram.io.rData

/** The wrapper's contract: the text of the body, the name that identifies a body, and the two sides that have to agree
  * on the port names — firtool's instantiation and the emitted SystemVerilog.
  */
object SyncRamTest extends TestSuite:

    private val parameter = SyncRamTestParameter(8, 64).ram
    private val module = SyncRamModule.moduleName(parameter)

    val tests = Tests:
        test("body: a synchronous read and a guarded write"):
            val v = SyncRam.verilog(module, parameter)
            assert(v.contains(s"module $module("))
            assert(v.contains("input r_en"))
            assert(v.contains("input [5:0] r_addr"))
            assert(v.contains("output [7:0] r_data"))
            assert(v.contains("input w_en"))
            assert(v.contains("input [5:0] w_addr"))
            assert(v.contains("input [7:0] w_data"))
            assert(v.contains("reg [7:0] mem [0:63];"))
            assert(v.contains("if (w_en) mem[w_addr] <= w_data;"))
            assert(v.contains("if (r_en) r_data_reg <= mem[r_addr];"))

        test("write-first bypasses a same-edge write to the read address"):
            val writeFirst = parameter.copy(readUnderWrite = SyncRamReadUnderWrite.WriteFirst)
            val v = SyncRam.verilog(SyncRamModule.moduleName(writeFirst), writeFirst)
            assert(v.contains("if (r_en) r_data_reg <= (w_en && (w_addr == r_addr)) ? w_data : mem[r_addr];"))

        test("single-port body: a byte-strobed write at the shared address"):
            val oneRW = SyncRamParameter(32, 64, SyncRamPorts.OneRW, SyncRamReadUnderWrite.ReadFirst)
            val v = SyncRam.verilog(SyncRamModule.moduleName(oneRW), oneRW)
            assert(v.contains("module SyncRam_dataWidth32_depth64_ports1rw_readUnderWriteReadFirst("))
            assert(v.contains("input [5:0] addr"))
            assert(v.contains("input [3:0] w_mask"))
            assert(v.contains("if (w_mask[0]) mem[addr][7:0] <= w_data[7:0];"))
            assert(v.contains("if (w_mask[3]) mem[addr][31:24] <= w_data[31:24];"))
            assert(v.contains("r_data_reg <= mem[addr];"))

        test("the module name spells the whole interface"):
            assert(module == "SyncRam_dataWidth8_depth64_ports1r1w_readUnderWriteReadFirst")

        test("the enum tokens are the config file's, the flag's and the module name's one spelling"):
            assert(SyncRamPorts.parse("1r1w") == Right(SyncRamPorts.OneR1W))
            assert(SyncRamPorts.parse("2rw") == Right(SyncRamPorts.TwoRW))
            assert(SyncRamPorts.parse("twoRW").isLeft)
            assert(SyncRamReadUnderWrite.parse("WriteFirst") == Right(SyncRamReadUnderWrite.WriteFirst))
            assert(SyncRamReadUnderWrite.parse("write-first").isLeft)
            assert(upickle.default.read[SyncRamParameter](upickle.default.write(parameter)) == parameter)

        test("a lowering connects it by port name, and the dump emits the body"):
            val dir = os.temp.dir(prefix = "nzea-syncram")
            os.write.over(dir / "filelist.f", "SyncRamTestDesign.sv")
            try
                val sv = TestFlow.lower(
                    "SyncRamTestDesign",
                    cfg => SyncRamTestDesign.main(Array("config", cfg.toString, "--width", "8", "--depth", "64")),
                    cfg =>
                        SyncRamTestDesign.main(Array("design", cfg.toString))
                        // Emit right where the elaboration left the registry: it is process-wide, and readable as this
                        // design's modules only between this elaboration and the next.
                        SyncRam.emitModules(dir)
                )
                val file = s"$module.sv"
                assert(os.read(dir / file) == SyncRam.verilog(module, parameter))
                assert(os.read(dir / "filelist.f") == s"SyncRamTestDesign.sv\n$file\n")

                val flat = sv.replaceAll("//[^\n]*", "").replaceAll("\\s+", " ")
                // firtool's instantiation connects by the port names the body declares.
                assert(flat.contains(s"$module "))
                Seq("clock", "r_en", "r_addr", "r_data", "w_en", "w_addr", "w_data").foreach: port =>
                    assert(flat.contains(s".$port ("))
            finally
                SyncRam.reset()
                os.remove.all(dir)
