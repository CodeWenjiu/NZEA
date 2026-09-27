package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.{CoreConfig, Isa}
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
  val issue = Flipped(Valid(new DivInput(parameter.config)))
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

    // A word form reads the low 32 bits of an operand, extended by the op's signedness.
    def wordIn(v: Referable[UInt]): Referable[UInt] =
      val low: Node[Bits] = v.asBits.bits(31, 0)
      val signExtended: Node[Bits] = fill(width - 32, low.bit(31)) ## low
      wordSigned ? (signExtended.asUInt, (BigInt(0).B(width - 32) ## low).asUInt)
    val aIn: Referable[UInt] = if hasWord then (isWord ? (wordIn(a), a)) else a
    val bIn: Referable[UInt] = if hasWord then (isWord ? (wordIn(b), b)) else b

    val aNeg = Node(aIn.asBits).bit(width - 1)
    val bNeg = Node(bIn.asBits).bit(width - 1)

    val aAbs = aNeg ? (subWrap(0.U(width), aIn, width), aIn)
    val bAbs = bNeg ? (subWrap(0.U(width), bIn, width), bIn)
    val divisor = signed ? (bAbs, bIn)
    val dividend = signed ? (aAbs, aIn)

    val divByZero = bIn === 0.U(width)
    val overflow =
      signed & (aIn === (BigInt(1) << (width - 1)).U(width)) & (bIn === (BigInt((1 << width) - 1)).U(width))
    val allOnes: Referable[UInt] = BigInt((1 << width) - 1).U(width)
    val minInt: Referable[UInt] = (BigInt(1) << (width - 1)).U(width)

    // The last iteration's result comes from the value that iteration produces, not from the register it is held in.
    val shifted = (rq.asBits << 1).bits(workingWidth - 1, 0)
    val remHi = shifted.bits(workingWidth - 1, width).asUInt
    val ge = remHi >= divisor
    val newRem: Node[Bits] = (ge ? (subWrap(remHi, divisor, width), remHi)).asBits
    val newRq = (newRem ## shifted.bits(width - 1, 1) ## ge.asBits).asUInt

    val last = count === (width - 1).U(countWidth)
    val start = io.issue.valid & !regNext(io.issue.valid, false.B)

    val quoAbs = Node(newRq.asBits).bits(width - 1, 0).asUInt
    val remAbs = Node(newRq.asBits).bits(workingWidth - 1, width).asUInt
    val quo = (signed & (aNeg ^ bNeg)) ? (subWrap(0.U(width), quoAbs, width), quoAbs)
    val rem = (signed & aNeg) ? (subWrap(0.U(width), remAbs, width), remAbs)

    /** DIV/REM answer a zero divisor with all ones and the unsigned forms with the dividend; the signed overflow
      * answers with INT_MIN for the quotient and zero for the remainder. A word form answers with the same, extended —
      * `aIn` is already the extended dividend — and never takes the overflow path, whose operands cannot both hold.
      */
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

    val resultValue = mux1H(op, ops.variants.map(result), width)
    val byZeroValue = mux1H(op, ops.variants.map(byZero), width)
    val onOverflowValue = mux1H(op, ops.variants.map(onOverflow), width)

    when(!io.issue.valid) {
      state := sIdle
    }.otherwise {
      when(state === sIdle) {
        when(start) {
          rq := (0.B(width) ## dividend.asBits).asUInt
          count := 0.U(countWidth)
          // The two special cases are mutually exclusive (the divisor cannot be both zero and minus one).
          when(divByZero) { resultReg := byZeroValue; state := sDone }
          when(overflow) { resultReg := onOverflowValue; state := sDone }
          when(!(divByZero | overflow)) { state := sCalc }
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
    }

    io.done := state === sDone
    if hasWord then
      val resultWord: Node[UInt] =
        (fill(width - 32, resultReg.asBits.bit(31)) ## resultReg.asBits.bits(31, 0)).asUInt
      io.data := (isWord ? (resultWord, resultReg))
    else io.data := resultReg
