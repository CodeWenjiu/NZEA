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

/** Operands and op of one multiply. */
class MulInput(width: Int) extends Bundle:
  val opA = Aligned(UInt(width))
  val opB = Aligned(UInt(width))
  val op = Aligned(UInt(MulOp.width))

case class MulParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[MulParameter] = upickle.default.macroRW

class MulLayers(parameter: MulParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class MulIO(parameter: MulParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(Valid(new MulInput(parameter.width)))
  val done = Aligned(Bool())
  val data = Aligned(UInt(parameter.width))

class MulProbe(parameter: MulParameter) extends DVBundle[MulParameter, MulLayers](parameter)

/** Multi-cycle multiplier: one shift-add iteration per cycle, the accumulator as wide as the product.
  *
  * Both operands are extended to the product width (sign or zero as the op says), so the loop's unsigned arithmetic
  * over B's low bits already yields the true low bits of the product. B's sign is folded in as the initial accumulator,
  * `-A` shifted to B's sign position, which is exactly what an extra sign bit of the multiplier would contribute.
  *
  * The unit takes no flush. The middle holds the uop until the completion is reported, so a uop that disappears means
  * the completion was consumed (or abandoned) and the FSM returns to idle either way.
  */
@generator
object Mul extends Generator[MulParameter, MulLayers, MulIO, MulProbe]:
  override def moduleName(parameter: MulParameter): String = "Mul"

  def architecture(parameter: MulParameter) =
    val io = summon[Interface[MulIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.width
    val productWidth = 2 * width
    val countWidth = log2Ceil(width + 1)

    val a: Node[Bits] = Node(io.issue.bits.opA.asBits)
    val b: Node[Bits] = Node(io.issue.bits.opB.asBits)
    val op: Referable[UInt] = io.issue.bits.op

    val aSigned = (op === MulOp.Mul.lit.U(MulOp.width)) | (op === MulOp.Mulh.lit.U(MulOp.width)) |
      (op === MulOp.Mulhsu.lit.U(MulOp.width))
    val bSigned = (op === MulOp.Mul.lit.U(MulOp.width)) | (op === MulOp.Mulh.lit.U(MulOp.width))

    val aExt: Node[UInt] = aSigned ? ((fill(width, a.bit(width - 1)) ## a).asUInt, (0.B(width) ## a).asUInt)
    val correction: Node[UInt] =
      (bSigned & b.bit(width - 1)) ? (
        subWrap(0.U(productWidth), (a ## 0.B(width)).asUInt, productWidth),
        0.U(productWidth)
      )

    val acc = Reg(UInt(productWidth))
    val mcand = Reg(UInt(productWidth))
    val mp = Reg(UInt(width))
    val count = Reg(UInt(countWidth))
    val busy = RegInit(false.B)
    val doneReg = RegInit(false.B)

    val start = io.issue.valid & !regNext(io.issue.valid)
    val last = count === (width - 1).U(countWidth)

    when(busy & io.issue.valid) {
      acc := addWrap(acc, Node(mp.asBits).bit(0) ? (mcand, 0.U(productWidth)), productWidth)
      mcand := (mcand.asBits << 1).bits(productWidth - 1, 0).asUInt
      mp := (mp.asBits >> 1).pad(width).asUInt
      count := addWrap(count, 1.U(countWidth), countWidth)
      when(last) { doneReg := true.B }
    }
    when(!busy & start) {
      acc := correction
      mcand := aExt
      mp := io.issue.bits.opB
      count := 0.U(countWidth)
      busy := true.B
    }

    // A uop that is gone leaves nothing to complete; the same rule ends a finished multiply.
    when(!io.issue.valid | (busy & last)) { busy := false.B }
    when(!io.issue.valid) { doneReg := false.B }

    val product: Node[Bits] = Node(acc.asBits)
    val high: Node[Bits] = product.bits(productWidth - 1, width)
    io.done := doneReg
    io.data := mux1H(op, Seq(product.bits(width - 1, 0), high, high, high), width).asUInt
