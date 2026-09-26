package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operands and op of one divide. */
class DivInput(width: Int) extends Bundle:
  val opA = Aligned(UInt(width))
  val opB = Aligned(UInt(width))
  val op = Aligned(UInt(DivOp.width))

case class DivParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[DivParameter] = upickle.default.macroRW

class DivLayers(parameter: DivParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class DivIO(parameter: DivParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(Valid(new DivInput(parameter.width)))
  val done = Aligned(Bool())
  val data = Aligned(UInt(parameter.width))

class DivProbe(parameter: DivParameter) extends DVBundle[DivParameter, DivLayers](parameter)

/** Multi-cycle restoring divider: one shift-subtract iteration per cycle over the operands' magnitudes.
  *
  * The working register is `{remainder, quotient}`; each step shifts it left and subtracts the divisor when the
  * remainder holds, which is also the quotient bit. RISC-V divides magnitudes and takes the result's sign from the
  * operands, so both are negated up front and the sign is applied to the result; the two special cases — division by
  * zero, and the signed overflow INT_MIN / -1 — are answered from the operands without running the loop.
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

    val width = parameter.width
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

    val signed = (op === DivOp.Div.lit.U(DivOp.width)) | (op === DivOp.Rem.lit.U(DivOp.width))
    val aNeg = Node(a.asBits).bit(width - 1)
    val bNeg = Node(b.asBits).bit(width - 1)

    val aAbs = aNeg ? (subWrap(0.U(width), a, width), a)
    val bAbs = bNeg ? (subWrap(0.U(width), b, width), b)
    val divisor = signed ? (bAbs, b)
    val dividend = signed ? (aAbs, a)

    val divByZero = b === 0.U(width)
    val overflow =
      signed & (a === (BigInt(1) << (width - 1)).U(width)) & (b === (BigInt((1 << width) - 1)).U(width))
    val allOnes: Referable[UInt] = BigInt((1 << width) - 1).U(width)
    val minInt: Referable[UInt] = (BigInt(1) << (width - 1)).U(width)
    // Division by zero answers with all ones for DIV/REM and with the dividend for the unsigned forms; the signed
    // overflow answers with INT_MIN for DIV and zero for REM.
    val byZero: Node[UInt] = mux1H(op, Seq(allOnes, allOnes, a, a), width)
    val onOverflow: Node[UInt] = mux1H(op, Seq(minInt, allOnes, 0.U(width), a), width)

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
    val result = mux1H(op, Seq(quo, quoAbs, rem, remAbs), width)

    when(!io.issue.valid) {
      state := sIdle
    }.otherwise {
      when(state === sIdle) {
        when(start) {
          rq := (0.B(width) ## dividend.asBits).asUInt
          count := 0.U(countWidth)
          // The two special cases are mutually exclusive (the divisor cannot be both zero and minus one).
          when(divByZero) { resultReg := byZero; state := sDone }
          when(overflow) { resultReg := onOverflow; state := sDone }
          when(!(divByZero | overflow)) { state := sCalc }
        }
      }
      when(state === sCalc) {
        rq := newRq
        count := addWrap(count, 1.U(countWidth), countWidth)
        when(last) {
          resultReg := result
          state := sDone
        }
      }
    }

    io.done := state === sDone
    io.data := resultReg
