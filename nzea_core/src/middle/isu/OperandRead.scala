package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BypassLevel, CoreConfig}
import nzea_core.front.idu.IduOut
import nzea_core.isa.{Gpr, Isa, Operand, Srcs}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class OperandReadParameter(isa: Isa, defaultPc: Long, bypass: BypassLevel) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[OperandReadParameter] = upickle.default.macroRW

class OperandReadLayers(parameter: OperandReadParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class OperandReadIO(parameter: OperandReadParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())

  /** The payload whose operands are being placed, in the cycle ID/IS presents it. */
  val decoded = Flipped(new IduOut(parameter.config))

  /** The register file's read side: the indexes the payload names, the values the file answers with. */
  val rs1_index = Aligned(UInt(Gpr.indexWidth))
  val rs2_index = Aligned(UInt(Gpr.indexWidth))
  val rs1_data = Flipped(UInt(width))
  val rs2_data = Flipped(UInt(width))

  /** The bypass network's answers for the same two indexes. */
  val bypass_rs1 = Flipped(ValidIO(UInt(width)))
  val bypass_rs2 = Flipped(ValidIO(UInt(width)))

  /** The writers still in flight: the slot's instruction, and the retiring one. */
  val slot_valid = Flipped(Bool())
  val slot_rd_index = Flipped(UInt(Gpr.indexWidth))
  val wb_valid = Flipped(Bool())
  val wb_rd = Flipped(UInt(Gpr.indexWidth))

  /** The placed operands: the three slots the units read. */
  val src_a = Aligned(UInt(width))
  val src_b = Aligned(UInt(width))
  val src_c = Aligned(UInt(width))

  /** The read is placeable: every operand is settled — no in-flight writer the network cannot answer. */
  val ready = Aligned(Bool())

class OperandReadProbe(parameter: OperandReadParameter)
    extends DVBundle[OperandReadParameter, OperandReadLayers](parameter)

/** The read: the operand fetch between ID/IS and the files.
  *
  * The file answers the two indexes the payload names, the bypass network's answer wins where it is valid (it is the
  * younger producer), and the operand form places the result — with the immediate, the pc and zero — into the three
  * slots the units read. The pace back to ID/IS is this read's own: a payload that names a writer still in flight waits
  * for it, unless the bypass level reaches the writer's stage. That is the one wait the network cannot answer for a
  * consumer — a writer younger than its sources is one it does not carry — so the test lives here, beside the read that
  * takes the answers.
  */
@generator
object OperandRead extends Generator[OperandReadParameter, OperandReadLayers, OperandReadIO, OperandReadProbe]:
  override def moduleName(parameter: OperandReadParameter): String = "OperandRead"

  def architecture(parameter: OperandReadParameter) =
    val io = summon[Interface[OperandReadIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val indexWidth = Gpr.indexWidth

    /** The read: the file answers the two indexes the payload names, the network's answer wins where it is valid, and
      * the operand form places the result into the three slots the units read.
      */
    def placeOperands(): (Referable[UInt], Referable[UInt], Referable[UInt]) =
      val dec = io.decoded
      io.rs1_index := dec.rs1_index
      io.rs2_index := dec.rs2_index
      val rs1 = io.bypass_rs1.valid ? (io.bypass_rs1.bits, io.rs1_data)
      val rs2 = io.bypass_rs2.valid ? (io.bypass_rs2.bits, io.rs2_data)
      Srcs.resolve(
        dec.srcs,
        Seq[(Operand, Referable[UInt])](
          Operand.Rs1 -> rs1,
          Operand.Rs2 -> rs2,
          Operand.Imm -> dec.imm,
          Operand.Pc -> dec.pc,
          Operand.Zero -> 0.U(width)
        ),
        width
      )

    /** The read is placeable when no in-flight writer blocks it: the operand form once more, for the registers each
      * slot was read from — 0 for the slots no register feeds, which is also the index no instruction writes — and the
      * level's answer for how far the network reaches. A wait term the level covers folds to false and takes its
      * comparator with it.
      */
    def operandsReady(): Referable[Bool] =
      val (idxA, idxB, idxC) = Srcs.resolve(
        io.decoded.srcs,
        Seq[(Operand, Referable[UInt])](
          Operand.Rs1 -> io.decoded.rs1_index,
          Operand.Rs2 -> io.decoded.rs2_index,
          Operand.Imm -> 0.U(indexWidth),
          Operand.Pc -> 0.U(indexWidth),
          Operand.Zero -> 0.U(indexWidth)
        ),
        indexWidth
      )
      def reads(rd: Referable[UInt]): Referable[Bool] =
        (rd =/= 0.U(indexWidth)) & ((idxA === rd) | (idxB === rd) | (idxC === rd))
      val waitsForExecute =
        if parameter.bypass.execute then false.B else io.slot_valid & reads(io.slot_rd_index)
      val waitsForCommit =
        if parameter.bypass.commit then false.B else io.wb_valid & reads(io.wb_rd)
      !waitsForExecute & !waitsForCommit

    val (srcA, srcB, srcC) = placeOperands()
    io.src_a := srcA
    io.src_b := srcB
    io.src_c := srcC
    io.ready := operandsReady()
