package nzea_lab.generators

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_lab.{LabGenerator, ParamValues}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The course's register file: two asynchronous read ports and one synchronous write port, x0 hardwired. */
case class RegFileParameter(dataWidth: Int, addrWidth: Int) extends Parameter:
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(addrWidth >= 1 && addrWidth <= 16, s"addrWidth must be in 1..16, got $addrWidth")

given upickle.default.ReadWriter[RegFileParameter] = upickle.default.macroRW

class RegFileLayers(parameter: RegFileParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class RegFileIO(parameter: RegFileParameter) extends HWBundle(parameter):
    val clk = Flipped(Clock())
    val waddr = Flipped(UInt(parameter.addrWidth))
    val raddr1 = Flipped(UInt(parameter.addrWidth))
    val raddr2 = Flipped(UInt(parameter.addrWidth))
    val wen = Flipped(Bool())
    val wdata = Flipped(UInt(parameter.dataWidth))
    val rdata1 = Aligned(UInt(parameter.dataWidth))
    val rdata2 = Aligned(UInt(parameter.dataWidth))

class RegFileProbe(parameter: RegFileParameter) extends DVBundle[RegFileParameter, RegFileLayers](parameter)

/** The course's register file, built the way the core's `Arf` is: the file holds x1..x31, x0 is a constant zero, and a
  * read is a subaccess of the register vector. No reset port: the course's interface has none, and the benchmark
  * initializes the registers it uses.
  */
@generator
object RegFileGen
    extends Generator[RegFileParameter, RegFileLayers, RegFileIO, RegFileProbe],
      LabGenerator[RegFileParameter]:

    val name = "reg_file"

    val doc = "32x32 register file, 2R1W, x0 hardwired (course interface)"

    def parameter(values: ParamValues): RegFileParameter =
        RegFileParameter(values.int("dataWidth"), values.int("addrWidth"))

    override def moduleName(parameter: RegFileParameter): String = "reg_file"

    def architecture(parameter: RegFileParameter) =
        val io = summon[Interface[RegFileIO]]
        given ClockScope = ClockScope.posedge(io.clk)

        val depth = 1 << parameter.addrWidth
        val xregs = Reg(Vec(depth - 1, UInt(parameter.dataWidth)))
        val gprs = (0.U(parameter.dataWidth) +: (0 until depth - 1).map(index => xregs.ref(index))).toVec

        when(io.wen & (io.waddr =/= 0.U(parameter.addrWidth))) {
            for index <- 0 until depth - 1 do
                when(io.waddr === (index + 1).U(parameter.addrWidth)) {
                    xregs(index) := io.wdata
                }
        }

        io.rdata1 := gprs.ref(io.raddr1)
        io.rdata2 := gprs.ref(io.raddr2)
