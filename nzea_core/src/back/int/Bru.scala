package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.BpuLevel
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BruParameter(width: Int, bpu: BpuLevel) extends Parameter

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
  * that prediction is the fall-through, which is what the mispredict test below folds away.
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
    val taken = mux1H(
      op,
      Seq(
        true.B,
        true.B,
        io.rs1 === io.rs2,
        io.rs1 =/= io.rs2,
        (rs1Bits.asSInt < rs2Bits.asSInt),
        (rs1Bits.asSInt >= rs2Bits.asSInt),
        io.rs1 < io.rs2,
        io.rs1 >= io.rs2
      )
    )

    io.taken := taken
    io.target := target
    io.nextPc := taken ? (target, io.fallThrough)

    // The prediction check, `predNextPc =/= nextPc`: where the stream went against where the branch resolved. The
    // flush's own copy of the test is the write-back stage's (`is_branch & next_pc =/= pred_next_pc`, made against its
    // registers); this output is what the resolve-side counters report on. At `Off` the prediction is the fall-through,
    // which folds the check into the offset not being 4 for the pc-relative forms — instruction-static, the sequential
    // baseline's own mispredict. JALR bases its target on a register, so it always counts as a redirect there.
    val mispredict: Referable[Bool] =
      if parameter.bpu.predicted then io.nextPc =/= io.predNextPc
      else taken & (isJalr | (io.offset =/= 4.U(width)))
    io.mispredict := mispredict
