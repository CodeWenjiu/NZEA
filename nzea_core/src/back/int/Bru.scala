package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.{ExecSlot, FuView}
import nzea_core.config.{BpuConfig, CoreConfig}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BruParameter(width: Int, bpu: Option[BpuConfig]) extends Parameter

given upickle.default.ReadWriter[BruParameter] = upickle.default.macroRW

class BruLayers(parameter: BruParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The branch unit's own input: its op, the target's base and offset, the two compared registers, and the fetch's two
  * carried pcs. The base arrives placed by the instruction's form — the pc every jump and branch bases on, the register
  * operand JALR bases on — so the unit never selects it, which is what keeps its op decode off the adder's path.
  */
class BruInput(width: Int) extends Bundle:
    val op = Aligned(UInt(BruOp.width))
    val base = Aligned(UInt(width))
    val offset = Aligned(UInt(width))

    /** The compared operands, from the pool's third and fourth slots: the branch's two registers. A jump compares
      * nothing, so its form leaves those slots zero and what they carry is never selected.
      */
    val rs1 = Aligned(UInt(width))
    val rs2 = Aligned(UInt(width))
    val predNextPc = Aligned(UInt(width))
    val fallThrough = Aligned(UInt(width))

object BruInput:

    given FuView[BruInput] with

        def view(u: Referable[ExecSlot], config: CoreConfig)(using
            Arena,
            Context,
            Block,
            sourcecode.File,
            sourcecode.Line,
            sourcecode.Name.Machine,
            InstanceContext,
            TypeImpl
        ): Wire[BruInput] =
            val in = Wire(new BruInput(config.width))
            in.op := Encoding.take(IntOp.set(config.isa).payload(u.op), BruOp.width)
            in.base := u.src.ref(0)
            in.offset := u.src.ref(1)
            in.rs1 := u.src.ref(2)
            in.rs2 := u.src.ref(3)
            in.predNextPc := u.next_pc
            in.fallThrough := u.fall_through
            in

class BruIO(parameter: BruParameter) extends HWBundle(parameter):
    val in = Flipped(new BruInput(parameter.width))
    val taken = Aligned(Bool())
    val mispredict = Aligned(Bool())
    val nextPc = Aligned(UInt(parameter.width))
    val target = Aligned(UInt(parameter.width))

class BruProbe(parameter: BruParameter) extends DVBundle[BruParameter, BruLayers](parameter)

/** Branch unit: resolves a jump or branch into a taken flag, a target and the pc to continue from.
  *
  * The prediction is an input, not this unit's knowledge: the pc to continue from when the branch is not taken is the
  * fall-through, and a predicted level resolves against the fetch's prediction. A predictor that lands changes where
  * the prediction comes from, not this interface; at `Off` that prediction is the fall-through. Combinational, and it
  * carries no valid: the path gates the redirect with the tag it issued.
  */
@generator
object Bru extends Generator[BruParameter, BruLayers, BruIO, BruProbe]:
    override def moduleName(parameter: BruParameter): String = "Bru"

    def architecture(parameter: BruParameter) =
        val io = summon[Interface[BruIO]]

        val width = parameter.width
        val op: Referable[UInt] = io.in.op
        val rs1Bits: Node[Bits] = Node(io.in.rs1.asBits)
        val rs2Bits: Node[Bits] = Node(io.in.rs2.asBits)

        val target = prefixAdder(io.in.base, io.in.offset, false.B, width)
        // The orderings are borrow scans, not the `<` primitive: left to synthesis the primitive serializes into a
        // one-gate-per-bit borrow ripple (see `lessThan`), and four of the eight outcomes ride it — the same cone the ALU
        // already took off its compares. Signed shares the unsigned scan (when the signs differ, the negative side is the
        // smaller), and equality is one compare with its negation beside it.
        val lessU = lessThan(io.in.rs1, io.in.rs2, width)
        val signDiff: Referable[Bool] = rs1Bits.bit(width - 1) ^ rs2Bits.bit(width - 1)
        val lessS: Referable[Bool] = signDiff ? (rs1Bits.bit(width - 1), lessU)
        val equal: Referable[Bool] = io.in.rs1 === io.in.rs2
        val taken = mux1H(
            op,
            Seq(true.B, true.B, equal, !equal, lessS, !lessS, lessU, !lessU)
        )

        io.taken := taken
        io.target := target
        io.nextPc := taken ? (target, io.in.fallThrough)

        // The prediction check, `predNextPc =/= nextPc`: where the stream went against where the branch resolved. The
        // verdict is made here and rides the path's result to the retire record, and it is the same fact the
        // resolve-side counters report on. At `Off` the prediction is the fall-through, so the check reduces to the
        // resolved next pc not being it.
        //
        // `nextPc` is `taken ? target : fallThrough`, so the check is its two arms compared apart and `taken` selects at
        // the end: a compare hung off the resolved pc waits on the comparator's whole chain, while the arms start where
        // their inputs do — the fall-through arm at registers, the target arm at the adder — beside the comparator
        // instead of behind it. At `Off` the fall-through arm folds to a constant.
        val tgtMismatch: Referable[Bool] = io.target =/= io.in.predNextPc
        val fallMismatch: Referable[Bool] = io.in.fallThrough =/= io.in.predNextPc
        val mispredict: Referable[Bool] = taken ? (tgtMismatch, fallMismatch)
        io.mispredict := mispredict
