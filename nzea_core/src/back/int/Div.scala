package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.{ExecSlot, FuView, IssueSlot}
import nzea_core.isa.Isa
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operands and op of one divide. */
class DivInput(config: CoreConfig) extends Bundle:
    val opA = Aligned(UInt(config.width))
    val opB = Aligned(UInt(config.width))
    val op = Aligned(UInt(DivOp.set(config.isa).width))

object DivInput:

    /** The divider's view of the uop's union: the pool's first two slots, and its slice of the shared op window. */
    given FuView[DivInput] with

        def view(u: Referable[ExecSlot], config: CoreConfig)(using
            Arena,
            Context,
            Block,
            sourcecode.File,
            sourcecode.Line,
            sourcecode.Name.Machine,
            InstanceContext,
            TypeImpl
        ): Wire[DivInput] =
            val in = Wire(new DivInput(config))
            in.opA := u.src.ref(0)
            in.opB := u.src.ref(1)
            in.op := Encoding.take(IntOp.set(config.isa).payload(u.op), DivOp.set(config.isa).width)
            in

case class DivParameter(isa: Isa, defaultPc: Long) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[DivParameter] = upickle.default.macroRW

class DivLayers(parameter: DivParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class DivIO(parameter: DivParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val issue = Flipped(new IssueSlot(new DivInput(parameter.config)))
    val done = Aligned(Bool())
    val data = Aligned(UInt(parameter.config.width))

class DivProbe(parameter: DivParameter) extends DVBundle[DivParameter, DivLayers](parameter)

/** Multi-cycle restoring divider: one shift, one borrow scan and one conditional subtract per cycle over the operands'
  * magnitudes — the legacy `DIV` FU's FSM (`legacy/nzea_core/src/back/int/DIV.scala`), rebuilt on today's issue/done
  * contract and with the arithmetic rearranged so the iteration holds one carry network instead of a comparator chained
  * into a subtractor.
  *
  * The loop's operands are latched at issue: the divisor's magnitude, the two signs and the op the result will be read
  * under are registers for the whole residence, so nothing in the iteration waits on operand decode — the deficit of
  * the old shape, where the divide cone started at the issue register's own op field. The sign fix and the op select
  * are deferred to the completion cycle and run on the loop's registered raw pair, so the iteration and the result
  * select never share a path: the loop's worst path is shift, borrow, mux.
  *
  * The unit takes no flush: the middle holds the uop until the completion is reported, so a uop that disappears means
  * the completion was consumed (or abandoned) and the FSM returns to idle either way. RISC-V's special cases (a zero
  * divisor, and the signed overflow) are answered from the operands at issue and skip the loop.
  *
  * The word forms ([[DivOp]]'s block of them, only where the registers are 64 bits wide) divide the low 32 bits with
  * that width's own sign rules; the XLEN-wide loop over operands extended the same way produces that low word, and each
  * special case answers with the word's extension.
  */
@generator
object Div extends Generator[DivParameter, DivLayers, DivIO, DivProbe]:
    override def moduleName(parameter: DivParameter): String = "Div"

    def architecture(parameter: DivParameter) =
        val io = summon[Interface[DivIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val ops = DivOp.set(parameter.config.isa)
        val hasWord = ops.contains(DivOp.Divw)
        val countWidth = log2Ceil(width + 1)

        val sIdle = 0.U(2)
        val sCalc = 1.U(2)
        val sDone = 2.U(2)

        /** `-x` as its own prefix network: `~x + 1`. */
        def negate(x: Referable[UInt]): Node[UInt] =
            val xBits: Node[Bits] = Node(x.asBits)
            prefixAdder((~xBits).asUInt, BigInt(0).U(width), true.B, width)

        /** The issue-time picture of one divide, sampled into registers on `first`. */
        val u = io.issue.bits
        val aBits: Node[Bits] = Node(u.opA.asBits)
        val bBits: Node[Bits] = Node(u.opB.asBits)

        def is(v: DivOp): Node[Bool] = u.op === v.lit.U(ops.width)
        val wordSigned: Referable[Bool] = if hasWord then (is(DivOp.Divw) | is(DivOp.Remw)) else false.B
        val signed: Referable[Bool] = is(DivOp.Div) | is(DivOp.Rem) | wordSigned
        val isWord: Referable[Bool] = if hasWord then (wordSigned | is(DivOp.Divuw) | is(DivOp.Remuw)) else false.B

        /** A word form reads the low 32 bits of each operand, extended by the op's signedness. */
        def wordIn(v: Node[Bits]): Node[UInt] =
            val low: Node[Bits] = v.bits(31, 0)
            val signExtended: Node[Bits] = fill(width - 32, low.bit(31)) ## low
            (wordSigned ? (signExtended, BigInt(0).B(width - 32) ## low)).asUInt

        val aIn: Node[UInt] = if hasWord then (isWord ? (wordIn(aBits), aBits.asUInt)) else aBits.asUInt
        val bIn: Node[UInt] = if hasWord then (isWord ? (wordIn(bBits), bBits.asUInt)) else bBits.asUInt

        val aNeg: Node[Bool] = Node(aIn.asBits).bit(width - 1)
        val bNeg: Node[Bool] = Node(bIn.asBits).bit(width - 1)

        // The loop divides magnitudes: the signed forms pre-negate, the unsigned ones pass through.
        val aAbs: Node[UInt] = aNeg ? (negate(aIn), aIn)
        val bAbs: Node[UInt] = bNeg ? (negate(bIn), bIn)
        val dividend: Node[UInt] = signed ? (aAbs, aIn)
        val divisor: Referable[UInt] = signed ? (bAbs, bIn)

        // The two special cases are mutually exclusive (the divisor cannot be both zero and minus one) and both answer
        // without running the loop.
        val divByZero = bIn === 0.U(width)
        val allOnes: Referable[UInt] = ((BigInt(1) << width) - 1).U(width)
        val minInt: Referable[UInt] = (BigInt(1) << (width - 1)).U(width)
        val overflow = signed & (aIn === minInt) & (bIn === allOnes)

        def byZero(v: DivOp): Referable[UInt] = v match
            case DivOp.Div | DivOp.Divu | DivOp.Divw | DivOp.Divuw => allOnes
            case DivOp.Rem | DivOp.Remu | DivOp.Remw | DivOp.Remuw => aIn

        def onOverflow(v: DivOp): Referable[UInt] = v match
            case DivOp.Div | DivOp.Divw   => minInt
            case DivOp.Divu | DivOp.Divuw => allOnes
            case DivOp.Rem | DivOp.Remw   => 0.U(width)
            case DivOp.Remu | DivOp.Remuw => aIn

        val special = divByZero | overflow
        val specialValue: Node[UInt] = divByZero ? (
            mux1H(u.op, ops.variants.map(byZero), width),
            mux1H(u.op, ops.variants.map(onOverflow), width)
        )

        val state = RegInit(sIdle)
        val count = Reg(UInt(countWidth))
        val rq = Reg(UInt(2 * width))
        val divReg = Reg(UInt(width))
        val opReg = Reg(UInt(ops.width))
        val quoNeg = Reg(Bool())
        val remNeg = Reg(Bool())
        val specialReg = Reg(Bool())
        val specialValueReg = Reg(UInt(width))

        val start = io.issue.valid & io.issue.first
        val last = count === (width - 1).U(countWidth)

        /** The FSM, with the loop's operands latched at issue and the whole line cleared when the uop disappears. */
        def fsm(): Unit =
            when(!io.issue.valid) {
                state := sIdle
            }.otherwise {
                when(state === sIdle) {
                    when(start) {
                        divReg := divisor
                        opReg := u.op
                        quoNeg := signed & (aNeg ^ bNeg)
                        remNeg := signed & aNeg
                        specialReg := special
                        specialValueReg := specialValue
                        rq := (BigInt(0).B(width) ## dividend.asBits).asUInt
                        count := 0.U(countWidth)
                        state := special ? (sDone, sCalc)
                    }
                }
                when(state === sCalc) {
                    // One borrow scan per iteration: the difference and the borrow come out of the same network.
                    val shifted: Node[UInt] = (rq.asBits << 1).bits(2 * width - 1, 0).asUInt
                    val remHi: Node[UInt] = Node(shifted.asBits).bits(2 * width - 1, width).asUInt
                    val (difference, borrow) = prefixSub(remHi, divReg, width)
                    val newRem: Node[UInt] = borrow ? (remHi, difference)
                    val shiftedLow: Node[Bits] = Node(shifted.asBits).bits(width - 1, 1)
                    val newRq: Node[UInt] = (newRem.asBits ## shiftedLow ## (!borrow).asBits).asUInt
                    rq := newRq
                    count := addWrap(count, 1.U(countWidth), countWidth)
                    // The completion state lasts exactly one cycle — the FSM leaves it on its own — so `done` is a
                    // pulse by construction, with nothing to clear for the next divide.
                    when(last) { state := sDone }
                }
                when(state === sDone) {
                    state := sIdle
                }
            }

        fsm()

        /** The completion cycle's fixup: the loop's raw magnitude pair, sign and op interpretation applied here, one
          * cycle after the loop — never on the iteration's path.
          */
        def fixup(): Unit =
            val quoAbs: Node[UInt] = Node(rq.asBits).bits(width - 1, 0).asUInt
            val remAbs: Node[UInt] = Node(rq.asBits).bits(2 * width - 1, width).asUInt
            val quo: Node[UInt] = quoNeg ? (negate(quoAbs), quoAbs)
            val rem: Node[UInt] = remNeg ? (negate(remAbs), remAbs)

            def result(v: DivOp): Referable[UInt] = v match
                case DivOp.Div | DivOp.Divw   => quo
                case DivOp.Divu | DivOp.Divuw => quoAbs
                case DivOp.Rem | DivOp.Remw   => rem
                case DivOp.Remu | DivOp.Remuw => remAbs

            val fixed: Node[UInt] = mux1H(opReg, ops.variants.map(result), width)
            val selected: Node[UInt] = specialReg ? (specialValueReg, fixed)
            val out: Node[UInt] =
                if hasWord then
                    val opR = opReg
                    val isWordReg =
                        (opR === DivOp.Divw.lit.U(ops.width)) | (opR === DivOp.Remw.lit.U(ops.width)) |
                            (opR === DivOp.Divuw.lit.U(ops.width)) | (opR === DivOp.Remuw.lit.U(ops.width))
                    val selectedBits: Node[Bits] = Node(selected.asBits)
                    val word: Node[UInt] =
                        (fill(width - 32, selectedBits.bit(31)) ## selectedBits.bits(31, 0)).asUInt
                    isWordReg ? (word, selected)
                else selected
            io.data := out

            io.done := state === sDone

        fixup()
