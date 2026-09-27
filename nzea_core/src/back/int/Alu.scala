package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.{CoreConfig, Isa}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class AluParameter(isa: Isa, defaultPc: Long) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[AluParameter] = upickle.default.macroRW

class AluLayers(parameter: AluParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class AluIO(parameter: AluParameter) extends HWBundle(parameter):
  private val ops = AluOp.set(parameter.config.isa)
  val op = Flipped(UInt(ops.width))
  val opA = Flipped(UInt(parameter.config.width))
  val opB = Flipped(UInt(parameter.config.width))
  val data = Aligned(UInt(parameter.config.width))

class AluProbe(parameter: AluParameter) extends DVBundle[AluParameter, AluLayers](parameter)

/** Integer ALU: add/sub, the three logic ops, the three shifts and the two compares, selected by [[AluOp]]'s code —
  * plus the word forms an ISA with 64-bit registers adds, which are the same ops on the low word with the result
  * sign-extended.
  *
  * The operands arrive resolved — decode filled the uop's slots — so the unit selects nothing, and being combinational
  * it carries no valid: the path gates its result with the tag it issued.
  *
  * The one-hot encoding is load bearing here, because [[AluOp]]'s declaration order groups the ops: bits `[1:0]` are
  * arithmetic, `[4:2]` logic, `[7:5]` shifts and `[9:8]` compares, and the word block starts at [[AluOp.baseWidth]]
  * mirroring the arithmetic and shift groups. Each group is therefore one `mux1H` over its own slice — the base slice
  * ORed with the word slice shifted down onto it — and the class that selects between the groups is an OR of the
  * group's bits.
  */
@generator
object Alu extends Generator[AluParameter, AluLayers, AluIO, AluProbe]:
  override def moduleName(parameter: AluParameter): String = "Alu"

  def architecture(parameter: AluParameter) =
    val io = summon[Interface[AluIO]]

    val width = parameter.config.width
    val ops = AluOp.set(parameter.config.isa)
    val hasWord = ops.contains(AluOp.Addw)
    val opBits: Node[Bits] = Node(io.op.asBits)
    val aBitsFull: Node[Bits] = Node(io.opA.asBits)
    val bBits: Node[Bits] = Node(io.opB.asBits)

    // Word mode: any bit of the word block being set. The shift amount is then the low five bits, the operand is the low
    // word zero-extended (the low bits of a sum or a left shift are the same either way, and a logical right shift wants
    // zeros above it), the arithmetic fill masks 32 bits and takes its sign from bit 31, and the result is
    // sign-extended back.
    val wordWidth = 32
    val wordSel: Referable[Bool] =
      if hasWord then
        val block = opBits.bits(ops.width - 1, AluOp.baseWidth)
        block.asUInt =/= 0.U(ops.width - AluOp.baseWidth)
      else false.B
    val shamtFull: Node[Bits] = bBits.bits(log2Ceil(width) - 1, 0)
    val shamt: Node[UInt] =
      if hasWord then (wordSel ? (bBits.bits(4, 0).pad(log2Ceil(width)), shamtFull)).asUInt
      else shamtFull.asUInt
    val aBits: Node[Bits] =
      if hasWord then
        val low: Node[Bits] = aBitsFull.bits(wordWidth - 1, 0)
        wordSel ? ((fill(width - wordWidth, false.B) ## low), aBitsFull)
      else aBitsFull

    val signFill: Node[Bits] = fill(width, aBitsFull.bit(width - 1))
    val wordFill: Node[Bits] = fill(wordWidth, aBitsFull.bit(wordWidth - 1))

    // Tree shifters, not barrel shifters: selecting a constant shift is free rewiring, so only the select network is
    // left. Arithmetic right shift rides on the logical one: a signed constant shift folds back into a logical shift
    // (the sign is dropped), so the fill is explicit, as a tree of constant masks.
    val sll: Node[UInt] =
      muxTree(shamt, (0 until width).map(i => (aBits.asUInt << i).asBits.bits(width - 1, 0).asUInt))
    val srl: Node[UInt] =
      muxTree(shamt, (0 until width).map(i => (aBits.asUInt >> i).asBits.pad(width).asUInt))
    val sra: Node[UInt] = muxTree(
      shamt,
      (0 until width).map: i =>
        val fullMask = if i == 0 then BigInt(0) else ((BigInt(1) << i) - 1) << (width - i)
        val fill =
          if hasWord then
            val wordMask = if i == 0 || i >= wordWidth then BigInt(0) else ((BigInt(1) << i) - 1) << (wordWidth - i)
            wordSel ? ((wordMask.U(width).asBits & wordFill), (fullMask.U(width).asBits & signFill))
          else (fullMask.U(width).asBits & signFill)
        ((aBits.asUInt >> i).asBits.pad(width) | fill).asUInt
    )

    val add = prefixAdder(io.opA, io.opB, false.B, width)
    val sub = prefixAdder(io.opA, (~bBits).asUInt, true.B, width)
    val slt = (aBits.asSInt < bBits.asSInt) ? (1.U(width), 0.U(width))
    val sltu = (io.opA < io.opB) ? (1.U(width), 0.U(width))

    // The base groups keep their positions; the word block mirrors the arithmetic and shift groups above baseWidth. A
    // group's select is its base slice OR its word slice, both read as the group's own bits: the two slices sit at the
    // same positions inside their own slice, so the group select is a per-bit OR, not a shift.
    val arithWord = if hasWord then opBits.bit(AluOp.baseWidth) | opBits.bit(AluOp.baseWidth + 1) else false.B
    val shiftWordSel: Node[UInt] =
      if hasWord then Node(opBits.bits(7, 5) | opBits.bits(AluOp.baseWidth + 4, AluOp.baseWidth + 2)).asUInt
      else Node(opBits.bits(7, 5)).asUInt
    val subSel = opBits.bit(1) | (if hasWord then opBits.bit(AluOp.baseWidth + 1) else false.B)

    val arith = subSel ? (sub, add)
    val logic = mux1H(
      Node(opBits.bits(4, 2)).asUInt,
      Seq((aBits & bBits).asUInt, (aBits | bBits).asUInt, (aBits ^ bBits).asUInt),
      width
    )
    val shift = mux1H(shiftWordSel, Seq(sll, srl, sra), width)
    val cmp = opBits.bit(9) ? (sltu, slt)
    val arithSel = opBits.bit(0) | opBits.bit(1) | arithWord
    val logicSel = opBits.bit(2) | opBits.bit(3) | opBits.bit(4)
    val shiftSel =
      opBits.bit(5) | opBits.bit(6) | opBits.bit(7) |
        (if hasWord then
           (opBits.bit(AluOp.baseWidth + 2) | opBits.bit(AluOp.baseWidth + 3) |
             opBits.bit(AluOp.baseWidth + 4))
         else false.B)
    val cmpSel = opBits.bit(8) | opBits.bit(9)
    val classSel: Node[UInt] = (cmpSel.asBits ## shiftSel.asBits ## logicSel.asBits ## arithSel.asBits).asUInt

    val data: Node[UInt] = mux1H(classSel, Seq(arith, logic, shift, cmp), width)
    if hasWord then
      val dataWord: Node[UInt] =
        (fill(width - wordWidth, data.asBits.bit(wordWidth - 1)) ## data.asBits.bits(wordWidth - 1, 0)).asUInt
      io.data := (wordSel ? (dataWord, data))
    else io.data := data
