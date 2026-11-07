package nzea_lab.generators

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_lab.{LabGenerator, ParamValues}
import nzea_rtl.stdlib.{lessThan, muxTree, prefixAdderCarry, prefixSub}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The course's ALU interface: a data width, three bits of operation, and the result with its flags. */
case class AluParameter(dataWidth: Int) extends Parameter:
    require(dataWidth >= 2, s"dataWidth must be >= 2, got $dataWidth")

given upickle.default.ReadWriter[AluParameter] = upickle.default.macroRW

class AluLayers(parameter: AluParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class AluIO(parameter: AluParameter) extends HWBundle(parameter):
    val A = Flipped(UInt(parameter.dataWidth))
    val B = Flipped(UInt(parameter.dataWidth))
    val ALUop = Flipped(UInt(3))
    val Overflow = Aligned(Bool())
    val CarryOut = Aligned(Bool())
    val Zero = Aligned(Bool())
    val Result = Aligned(UInt(parameter.dataWidth))

class AluProbe(parameter: AluParameter) extends DVBundle[AluParameter, AluLayers](parameter)

/** The lab's combinational ALU: the eight operations the course's `alu.v` declares, in its opcode order.
  *
  * `CarryOut` follows the course's reference: the carry out for ADD and the unsigned borrow for SUB, zero otherwise.
  * `Overflow` is the signed overflow of ADD/SUB, zero otherwise. `Zero` is the result being zero for every operation.
  */
@generator
object AluGen extends Generator[AluParameter, AluLayers, AluIO, AluProbe], LabGenerator[AluParameter]:

    val name = "alu"

    val doc = "8-operation combinational ALU (course interface)"

    def parameter(values: ParamValues): AluParameter = AluParameter(values.int("dataWidth"))

    override def moduleName(parameter: AluParameter): String = "alu"

    def architecture(parameter: AluParameter) =
        val io = summon[Interface[AluIO]]
        val width = parameter.dataWidth

        val aBits: Node[Bits] = Node(io.A.asBits)
        val bBits: Node[Bits] = Node(io.B.asBits)

        val (sum, carry) = prefixAdderCarry(io.A, io.B, false.B, width)
        val (difference, borrow) = prefixSub(io.A, io.B, width)

        val andValue = Node(aBits & bBits).asUInt
        val orValue = Node(aBits | bBits).asUInt
        val xorValue = Node(aBits ^ bBits).asUInt
        val norValue = Node(~(aBits | bBits)).asUInt

        val aSign = aBits.bit(width - 1)
        val bSign = bBits.bit(width - 1)
        val signsDiffer = aSign ^ bSign
        val unsignedLess = lessThan(io.A, io.B, width)
        val signedLess = signsDiffer ? (aSign, unsignedLess)
        val signedLessValue = Node(BigInt(0).B(width - 1) ## signedLess.asBits).asUInt
        val unsignedLessValue = Node(BigInt(0).B(width - 1) ## unsignedLess.asBits).asUInt

        // Opcode order: 000 AND, 001 OR, 010 ADD, 011 SLTU, 100 XOR, 101 NOR, 110 SUB, 111 SLT.
        val values: Seq[Referable[UInt]] = Seq(
            andValue,
            orValue,
            sum,
            unsignedLessValue,
            xorValue,
            norValue,
            difference,
            signedLessValue
        )
        val result = muxTree(io.ALUop, values)

        val isAdd = io.ALUop === 2.U(3)
        val isSub = io.ALUop === 6.U(3)
        val resultSign = Node(result.asBits).bit(width - 1)
        val addOverflow = !(aSign ^ bSign) & (resultSign ^ aSign)
        val subOverflow = (aSign ^ bSign) & (resultSign ^ aSign)

        io.Result := result
        io.Zero := result === 0.U(width)
        io.CarryOut := (isAdd & carry) | (isSub & borrow)
        io.Overflow := (isAdd & addOverflow) | (isSub & subOverflow)
