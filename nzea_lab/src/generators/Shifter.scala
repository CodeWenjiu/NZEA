package nzea_lab.generators

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_lab.{LabGenerator, ParamValues}
import nzea_rtl.stdlib.{fill, log2Ceil, muxTree}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The course's shifter: a power-of-two data width, a shift amount, and two bits of operation. */
case class ShifterParameter(dataWidth: Int) extends Parameter:

    require(
        dataWidth >= 2 && (dataWidth & (dataWidth - 1)) == 0,
        s"dataWidth must be a power of two >= 2, got $dataWidth"
    )

given upickle.default.ReadWriter[ShifterParameter] = upickle.default.macroRW

class ShifterLayers(parameter: ShifterParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ShifterIO(parameter: ShifterParameter) extends HWBundle(parameter):
    val A = Flipped(UInt(parameter.dataWidth))
    val B = Flipped(UInt(log2Ceil(parameter.dataWidth)))
    val Shiftop = Flipped(UInt(2))
    val Result = Aligned(UInt(parameter.dataWidth))

class ShifterProbe(parameter: ShifterParameter) extends DVBundle[ShifterParameter, ShifterLayers](parameter)

/** The course's shifter: 00 left, 10 logical right, 11 arithmetic right, 01 zero.
  *
  * The shift amount is a constant per branch, so each of the three results is a `muxTree` over fixed shifts — the same
  * shape the core ALU's shifters use — and the operation is one more `muxTree`.
  */
@generator
object ShifterGen
    extends Generator[ShifterParameter, ShifterLayers, ShifterIO, ShifterProbe],
      LabGenerator[ShifterParameter]:

    val name = "shifter"

    val doc = "left / logical-right / arithmetic-right shifter (course interface)"

    def parameter(values: ParamValues): ShifterParameter =
        ShifterParameter(values.int("dataWidth"))

    override def moduleName(parameter: ShifterParameter): String = "shifter"

    def architecture(parameter: ShifterParameter) =
        val io = summon[Interface[ShifterIO]]
        val width = parameter.dataWidth
        val aBits: Node[Bits] = Node(io.A.asBits)
        val signFill: Node[Bits] = fill(width, aBits.bit(width - 1))

        val left = muxTree(io.B, (0 until width).map(shift => (io.A << shift).asBits.bits(width - 1, 0).asUInt))
        val logicalRight = muxTree(io.B, (0 until width).map(shift => (io.A >> shift).asBits.pad(width).asUInt))
        val arithmeticRight = muxTree(
            io.B,
            (0 until width).map { shift =>
                val fullMask =
                    if shift == 0 then BigInt(0) else ((BigInt(1) << shift) - 1) << (width - shift)
                ((io.A >> shift).asBits.pad(width) | (fullMask.U(width).asBits & signFill)).asUInt
            }
        )

        io.Result := muxTree(io.Shiftop, Seq(left, 0.U(width), logicalRight, arithmeticRight))
