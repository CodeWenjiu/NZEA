package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.Gpr
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The file's write port: one register per instruction, retired by the commit. */
class ArfWrite(width: Int) extends Bundle:
    val index = Aligned(UInt(Gpr.indexWidth))
    val data = Aligned(UInt(width))

/** One read port: the register the reader asks for, and the value the file answers with. */
class ArfReadPort(width: Int) extends Bundle:
    val index = Flipped(UInt(Gpr.indexWidth))
    val data = Aligned(UInt(width))

case class ArfParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[ArfParameter] = upickle.default.macroRW

class ArfLayers(parameter: ArfParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ArfIO(parameter: ArfParameter) extends HWBundle(parameter):
    private val width = parameter.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val write = Flipped(ValidIO(new ArfWrite(width)))
    val rs1 = Aligned(new ArfReadPort(width))
    val rs2 = Aligned(new ArfReadPort(width))

class ArfProbe(parameter: ArfParameter) extends DVBundle[ArfParameter, ArfLayers](parameter)

/** Architectural register file: the two read ports a decode's operand form needs and the one write port the commit
  * drives.
  *
  * Storage only: a read answers what the file holds, and the write lands at the end of its cycle. Anything younger than
  * the file — the write port's own cycle, the instruction still completing — is the forwarding network's to offer, not
  * this file's; a consumer selects between the network's answer and the file's read.
  */
@generator
object Arf extends Generator[ArfParameter, ArfLayers, ArfIO, ArfProbe]:
    override def moduleName(parameter: ArfParameter): String = "Arf"

    def architecture(parameter: ArfParameter) =
        val io = summon[Interface[ArfIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.width

        // x0 is the hardwired zero: the file holds x1..x31, so no flop is allocated for a register the decoder cannot name.
        // Every slot starts defined so the four-state sim sees the same reset state as the reference model.
        val xregs = Reg(Vec(Gpr.depth - 1, UInt(width)))
        val gprs = (0.U(width) +: (0 until Gpr.depth - 1).map(index => xregs.ref(index))).toVec

        when(!io.resetN.asBool) {
            for index <- 0 until Gpr.depth - 1 do xregs(index) := 0.U(width)
        }

        when(io.write.valid & (io.write.bits.index =/= 0.U(Gpr.indexWidth))) {
            for index <- 0 until Gpr.depth - 1 do
                when(io.write.bits.index === (index + 1).U(Gpr.indexWidth)) { xregs(index) := io.write.bits.data }
        }

        io.rs1.data := gprs.ref(io.rs1.index)
        io.rs2.data := gprs.ref(io.rs2.index)
