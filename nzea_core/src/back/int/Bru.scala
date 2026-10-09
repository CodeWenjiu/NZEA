package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.BpuConfig
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BruParameter(width: Int, bpu: Option[BpuConfig]) extends Parameter

given upickle.default.ReadWriter[BruParameter] = upickle.default.macroRW

class BruLayers(parameter: BruParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BruIO(parameter: BruParameter) extends HWBundle(parameter):
    val op = Flipped(UInt(BruOp.width))
    val pc = Flipped(UInt(parameter.width))
    val predNextPc = Flipped(UInt(parameter.width))

    /** The pc's own fall-through, for the paths the prediction did not take. */
    val fallThrough = Flipped(UInt(parameter.width))
    val offset = Flipped(UInt(parameter.width))
    val rs1 = Flipped(UInt(parameter.width))
    val rs2 = Flipped(UInt(parameter.width))
    val taken = Aligned(Bool())
    val mispredict = Aligned(Bool())
    val nextPc = Aligned(UInt(parameter.width))
    val target = Aligned(UInt(parameter.width))

class BruProbe(parameter: BruParameter) extends DVBundle[BruParameter, BruLayers](parameter)

/** Branch unit: resolves a jump or branch into a taken flag, a target and the pc to continue from.
  *
  * The prediction is an input, not this unit's knowledge: the pc to continue from when the branch is not taken is the
  * fall-through (a predicted level resolves against the fetch's prediction and redirects wherever the two disagree, in
  * either direction), so a predictor that lands changes where the prediction comes from, not this interface. At `Off`
  * that prediction is the fall-through.
  *
  * Combinational, and it carries no valid: the path gates the redirect with the tag it issued.
  */
@generator
object Bru extends Generator[BruParameter, BruLayers, BruIO, BruProbe]:
    override def moduleName(parameter: BruParameter): String = "Bru"

    def architecture(parameter: BruParameter) =
        val io = summon[Interface[BruIO]]

        val width = parameter.width
        val op: Referable[UInt] = io.op
        val rs1Bits: Node[Bits] = Node(io.rs1.asBits)
        val rs2Bits: Node[Bits] = Node(io.rs2.asBits)

        // JALR bases its target on its register operand; every other jump and branch bases on the pc.
        val isJalr = op === BruOp.JALR.lit.U(BruOp.width)
        val target = prefixAdder(isJalr ? (io.rs1, io.pc), io.offset, false.B, width)
        // The orderings are borrow scans, not the `<` primitive: left to synthesis the primitive serializes into a
        // one-gate-per-bit borrow ripple (see `lessThan`), and four of the eight outcomes ride it — the same cone the ALU
        // already took off its compares. Signed shares the unsigned scan (when the signs differ, the negative side is the
        // smaller), and equality is one compare with its negation beside it.
        val lessU = lessThan(io.rs1, io.rs2, width)
        val signDiff: Referable[Bool] = rs1Bits.bit(width - 1) ^ rs2Bits.bit(width - 1)
        val lessS: Referable[Bool] = signDiff ? (rs1Bits.bit(width - 1), lessU)
        val equal: Referable[Bool] = io.rs1 === io.rs2
        val taken = mux1H(
            op,
            Seq(true.B, true.B, equal, !equal, lessS, !lessS, lessU, !lessU)
        )

        io.taken := taken
        io.target := target
        io.nextPc := taken ? (target, io.fallThrough)

        // The prediction check, `predNextPc =/= nextPc`: where the stream went against where the branch resolved. The
        // verdict is made here and rides the path's result to the retire record — the record takes it as made instead of
        // rebuilding the comparator behind the dispatch's merge — and it is the same fact the resolve-side counters
        // report on. At `Off` the prediction is the fall-through, so the check reduces to the resolved next pc not being
        // it.
        val mispredict: Referable[Bool] = io.nextPc =/= io.predNextPc
        io.mispredict := mispredict
