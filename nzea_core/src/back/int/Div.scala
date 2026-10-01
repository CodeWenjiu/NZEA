package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.IssueSlot
import nzea_core.isa.Isa
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operands and op of one divide. */
class DivInput(config: CoreConfig) extends Bundle:
    val opA = Aligned(UInt(config.width))
    val opB = Aligned(UInt(config.width))
    val op = Aligned(UInt(DivOp.set(config.isa).width))

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

/** Multi-cycle restoring divider: one shift-subtract iteration per cycle over the operands' magnitudes.
  *
  * The working register is `{remainder, quotient}`; each step shifts it left and subtracts the divisor when the
  * remainder holds, which is also the quotient bit. RISC-V divides magnitudes and takes the result's sign from the
  * operands, so both are negated up front and the sign is applied to the result; the two special cases — division by
  * zero, and the signed overflow INT_MIN / -1 — are answered from the operands without running the loop.
  *
  * The word forms ([[DivOp]]'s block of them, only where the registers are 64 bits wide) divide the low 32 bits with
  * that width's own sign rules; the XLEN-wide loop over operands extended the same way produces that low word, and each
  * special case answers with the word's extension.
  *
  * The unit takes no flush: the middle holds the uop until the completion is reported, so a uop that disappears means
  * the completion was consumed (or abandoned) and the FSM returns to idle either way.
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
        val workingWidth = 2 * width
        val countWidth = log2Ceil(width + 1)

        val sIdle = 0.U(2)
        val sCalc = 1.U(2)
        val sDone = 2.U(2)

        val state = RegInit(sIdle)
        val count = Reg(UInt(countWidth))
        val rq = Reg(UInt(workingWidth))
        val resultReg = Reg(UInt(width))

        val op: Referable[UInt] = io.issue.bits.op
        val a = io.issue.bits.opA
        val b = io.issue.bits.opB

        def is(v: DivOp): Node[Bool] = op === v.lit.U(ops.width)

        val wordSigned: Referable[Bool] = if hasWord then (is(DivOp.Divw) | is(DivOp.Remw)) else false.B
        val signed: Referable[Bool] = is(DivOp.Div) | is(DivOp.Rem) | wordSigned
        val isWord: Referable[Bool] = if hasWord then (wordSigned | is(DivOp.Divuw) | is(DivOp.Remuw)) else false.B

        /** The operands, with a word form reading the low 32 bits of each, extended by the op's signedness. */
        def extendedOperands() =
            def wordIn(v: Referable[UInt]): Referable[UInt] =
                val low: Node[Bits] = v.asBits.bits(31, 0)
                val signExtended: Node[Bits] = fill(width - 32, low.bit(31)) ## low
                wordSigned ? (signExtended.asUInt, (BigInt(0).B(width - 32) ## low).asUInt)
            val aIn: Referable[UInt] = if hasWord then (isWord ? (wordIn(a), a)) else a
            val bIn: Referable[UInt] = if hasWord then (isWord ? (wordIn(b), b)) else b
            (aIn, bIn)

        val (aIn, bIn) = extendedOperands()

        /** The sign each operand brings, taken before the magnitudes are formed. */
        def signs(aIn: Referable[UInt], bIn: Referable[UInt]) =
            (Node(aIn.asBits).bit(width - 1), Node(bIn.asBits).bit(width - 1))

        val (aNeg, bNeg) = signs(aIn, bIn)

        /** The magnitudes the loop divides, with the unsigned forms passing the operands through. */
        def magnitudes(aIn: Referable[UInt], bIn: Referable[UInt], aNeg: Referable[Bool], bNeg: Referable[Bool]) =
            val aAbs = aNeg ? (subWrap(0.U(width), aIn, width), aIn)
            val bAbs = bNeg ? (subWrap(0.U(width), bIn, width), bIn)
            val divisor = signed ? (bAbs, bIn)
            val dividend = signed ? (aAbs, aIn)
            (dividend, divisor)

        val (dividend, divisor) = magnitudes(aIn, bIn, aNeg, bNeg)

        val divByZero = bIn === 0.U(width)
        // The all-ones forms are built in BigInt: `(1 << width) - 1` on an `Int` wraps its shift amount and goes to zero
        // from width 32 up, which would answer a zero divisor with zero instead of minus one.
        val allOnes: Referable[UInt] = ((BigInt(1) << width) - 1).U(width)
        val overflow = signed & (aIn === (BigInt(1) << (width - 1)).U(width)) & (bIn === allOnes)
        val minInt: Referable[UInt] = (BigInt(1) << (width - 1)).U(width)

        /** One shift-subtract step of the loop: shift `{remainder, quotient}` left and subtract the divisor where the
          * remainder holds, which is also the shifted-in quotient bit. The last iteration's result comes from the value
          * that iteration produces, not from the register it is held in.
          */
        def iterate(divisor: Referable[UInt]) =
            val shifted = (rq.asBits << 1).bits(workingWidth - 1, 0)
            val remHi = shifted.bits(workingWidth - 1, width).asUInt
            val ge = remHi >= divisor
            val newRem: Node[Bits] = (ge ? (subWrap(remHi, divisor, width), remHi)).asBits
            (newRem ## shifted.bits(width - 1, 1) ## ge.asBits).asUInt

        val newRq = iterate(divisor)
        val last = count === (width - 1).U(countWidth)
        val start = io.issue.valid & io.issue.first

        /** The loop's fixed-up results: each op's ending, plus the two special cases, all with the sign rules applied.
          * DIV/REM answer a zero divisor with all ones and the unsigned forms with the dividend; the signed overflow
          * answers with INT_MIN for the quotient and zero for the remainder. A word form answers with the same,
          * extended — `aIn` is already the extended dividend — and never takes the overflow path, whose operands cannot
          * both hold.
          */
        def completions(aNeg: Referable[Bool], bNeg: Referable[Bool], aIn: Referable[UInt], divisor: Referable[UInt]) =
            val quoAbs = Node(newRq.asBits).bits(width - 1, 0).asUInt
            val remAbs = Node(newRq.asBits).bits(workingWidth - 1, width).asUInt
            val quo = (signed & (aNeg ^ bNeg)) ? (subWrap(0.U(width), quoAbs, width), quoAbs)
            val rem = (signed & aNeg) ? (subWrap(0.U(width), remAbs, width), remAbs)
            def byZero(v: DivOp): Referable[UInt] = v match
                case DivOp.Div | DivOp.Divu | DivOp.Divw | DivOp.Divuw => allOnes
                case DivOp.Rem | DivOp.Remu | DivOp.Remw | DivOp.Remuw => aIn
            def onOverflow(v: DivOp): Referable[UInt] = v match
                case DivOp.Div | DivOp.Divw   => minInt
                case DivOp.Divu | DivOp.Divuw => allOnes
                case DivOp.Rem | DivOp.Remw   => 0.U(width)
                case DivOp.Remu | DivOp.Remuw => aIn
            def result(v: DivOp): Referable[UInt] = v match
                case DivOp.Div | DivOp.Divw   => quo
                case DivOp.Divu | DivOp.Divuw => quoAbs
                case DivOp.Rem | DivOp.Remw   => rem
                case DivOp.Remu | DivOp.Remuw => remAbs
            (
                mux1H(op, ops.variants.map(result), width),
                mux1H(op, ops.variants.map(byZero), width),
                mux1H(op, ops.variants.map(onOverflow), width)
            )

        val (resultValue, byZeroValue, onOverflowValue) = completions(aNeg, bNeg, aIn, divisor)

        // The two special cases are mutually exclusive (the divisor cannot be both zero and minus one) and both answer
        // without running the loop. Their tests stay on the value path: the state register's enables are only `start`,
        // `last` and the state decode.
        val special = divByZero | overflow
        val specialValue: Referable[UInt] = divByZero ? (byZeroValue, onOverflowValue)

        /** The FSM: idle until `start`, one iteration per cycle, then the one-cycle completion state. */
        def fsm(
            start: Referable[Bool],
            last: Referable[Bool],
            special: Referable[Bool],
            specialValue: Referable[UInt],
            resultValue: Referable[UInt]
        ): Unit =
            when(!io.issue.valid) {
                state := sIdle
            }.otherwise {
                when(state === sIdle) {
                    when(start) {
                        rq := (0.B(width) ## dividend.asBits).asUInt
                        count := 0.U(countWidth)
                        // Both completions write the result here; the loop overwrites it when its last iteration lands.
                        resultReg := specialValue
                        state := special ? (sDone, sCalc)
                    }
                }
                when(state === sCalc) {
                    rq := newRq
                    count := addWrap(count, 1.U(countWidth), countWidth)
                    when(last) {
                        resultReg := resultValue
                        state := sDone
                    }
                }
                // The completion state lasts exactly one cycle — the FSM leaves it on its own — so `done` is a pulse by
                // construction, with nothing to clear for the next divide.
                when(state === sDone) { state := sIdle }
            }

        fsm(start, last, special, specialValue, resultValue)

        io.done := state === sDone

        /** The register's value in the width the op answers with: a word form is the register's low word, extended. */
        def resultWord(): Referable[UInt] =
            if hasWord then
                val word: Node[UInt] =
                    (fill(width - 32, resultReg.asBits.bit(31)) ## resultReg.asBits.bits(31, 0)).asUInt
                isWord ? (word, resultReg)
            else resultReg

        io.data := resultWord()
