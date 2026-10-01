package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.isa.Isa
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
  * The op field is the one-hot code itself, in declaration order, so the result is one flat select: every op's value
  * built, gated by its own code bit and ORed as a balanced tree — one selection cone, where grouping the ops into
  * classes would put two in series on the execute loop's src-to-src bypass cone. The word block reuses its base twin's
  * value sign-extended from bit 31, which is wiring.
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
                        val wordMask =
                            if i == 0 || i >= wordWidth then BigInt(0) else ((BigInt(1) << i) - 1) << (wordWidth - i)
                        wordSel ? ((wordMask.U(width).asBits & wordFill), (fullMask.U(width).asBits & signFill))
                    else (fullMask.U(width).asBits & signFill)
                ((aBits.asUInt >> i).asBits.pad(width) | fill).asUInt
        )

        val add = prefixAdder(io.opA, io.opB, false.B, width)
        val sub = prefixAdder(io.opA, (~bBits).asUInt, true.B, width)
        // The compares are borrow scans, not the `<` primitive: left to synthesis the primitive serializes into a one-gate-
        // per-bit borrow ripple (see `lessThan`), which is what put the execute loop's longest cone on this class. The
        // one-bit answer is zero-extended by concatenation: `? (1, 0)` would spend two more gates on the cone for a value
        // that is a wire.
        val sltu = Node(BigInt(0).B(width - 1) ## lessThan(io.opA, io.opB, width).asBits)
        // Signed: when the signs differ the negative side is the smaller; otherwise the words order like unsigned.
        val signDiff: Referable[Bool] = aBits.bit(width - 1) ^ bBits.bit(width - 1)
        val slt = Node(
            BigInt(0)
                .B(width - 1) ## (signDiff ? (aBits.bit(width - 1), lessThan(aBits.asUInt, bBits.asUInt, width))).asBits
        )

        // The result: one flat one-hot select. The op field is the code itself, in declaration order, so no group/class
        // decode sits between the values and the select — every value is built and gated by its own bit, and the gates are
        // ORed as one balanced tree. That is one selection cone where a group mux feeding a class mux made two, and the
        // execute loop's src-to-src bypass cone is exactly where those two cones landed, in series. The word forms reuse
        // their base twin's value sign-extended from bit 31 — wiring, not gates — which retires the result-side word mux.
        val signExtWord: Referable[Bits] => Node[Bits] =
            v => Node(fill(width - wordWidth, v.bit(wordWidth - 1)) ## v.bits(wordWidth - 1, 0))
        val values: Seq[Referable[Bits]] =
            Seq(
                add.asBits,
                sub.asBits,
                (aBits & bBits),
                (aBits | bBits),
                (aBits ^ bBits),
                sll.asBits,
                srl.asBits,
                sra.asBits,
                slt,
                sltu
            ) ++
                (if hasWord then Seq(add.asBits, sub.asBits, sll.asBits, srl.asBits, sra.asBits).map(signExtWord)
                 else Seq.empty)
        // The select reads the field positionally: values are in code order and a gated set only drops a trailing block
        // (see `AluOp.set`), so a variant's position in the list is the code bit that selects it.
        require(
            ops.variants.zipWithIndex.forall { case (v, i) => v.ordinal == i },
            "the flat select reads the op field positionally; a gated set must only drop a trailing block"
        )
        io.data := mux1H(opBits.asUInt, values, width).asUInt
