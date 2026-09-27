package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.{CoreConfig, Isa}
import nzea_core.back.IssueSlot
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operands and op of one multiply. */
class MulInput(config: CoreConfig) extends Bundle:
  val opA = Aligned(UInt(config.width))
  val opB = Aligned(UInt(config.width))
  val op = Aligned(UInt(MulOp.set(config.isa).width))

case class MulParameter(isa: Isa, defaultPc: Long) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[MulParameter] = upickle.default.macroRW

class MulLayers(parameter: MulParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class MulIO(parameter: MulParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(new IssueSlot(new MulInput(parameter.config)))
  val done = Aligned(Bool())
  val data = Aligned(UInt(parameter.config.width))

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

    val width = parameter.config.width
    val ops = MulOp.set(parameter.config.isa)
    val hasWord = ops.contains(MulOp.Mulw)
    val productWidth = 2 * width
    val countWidth = log2Ceil(width + 1)

    val a: Node[Bits] = Node(io.issue.bits.opA.asBits)
    val b: Node[Bits] = Node(io.issue.bits.opB.asBits)
    val op: Referable[UInt] = io.issue.bits.op

    val mulw = if hasWord then (op === MulOp.Mulw.lit.U(ops.width)) else false.B
    val aSigned = (op === MulOp.Mul.lit.U(ops.width)) | (op === MulOp.Mulh.lit.U(ops.width)) |
      (op === MulOp.Mulhsu.lit.U(ops.width)) | mulw
    val bSigned = (op === MulOp.Mul.lit.U(ops.width)) | (op === MulOp.Mulh.lit.U(ops.width)) | mulw

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

    val start = io.issue.valid & io.issue.first
    val last = count === (width - 1).U(countWidth)
    val completing = busy & io.issue.valid & last

    when(busy & io.issue.valid) {
      acc := addWrap(acc, Node(mp.asBits).bit(0) ? (mcand, 0.U(productWidth)), productWidth)
      mcand := (mcand.asBits << 1).bits(productWidth - 1, 0).asUInt
      mp := (mp.asBits >> 1).pad(width).asUInt
      count := addWrap(count, 1.U(countWidth), countWidth)
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

    val product: Node[Bits] = Node(acc.asBits)
    val low: Node[Bits] = product.bits(width - 1, 0)
    val high: Node[Bits] = product.bits(productWidth - 1, width)
    // MULW is MUL's low word, sign-extended; the low word of a product depends on the low words of the operands, so the
    // same multiply answers both. An ISA without the word form has no extend to build.
    val lowWord: Node[Bits] =
      if hasWord then (fill(width - 32, low.bit(31)) ## low.bits(31, 0)) else low

    def data(v: MulOp): Referable[Bits] = v match
      case MulOp.Mul                               => low
      case MulOp.Mulh | MulOp.Mulhsu | MulOp.Mulhu => high
      case MulOp.Mulw                              => lowWord

    // One pulse per instruction, in the cycle after the last accumulate: a held level would still be high when the
    // next multiply arrives, since the middle admits it in the cycle this one commits.
    io.done := regNext(completing, false.B)
    io.data := mux1H(op, ops.variants.map(data), width).asUInt
