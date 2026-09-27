package nzea_core.front.idu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.{Decoupled, DecoupledIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.CoreConfig
import nzea_core.back.OpWindow
import nzea_core.front.ifu.FetchedInst
import nzea_core.isa.{Backend, Gpr, Srcs}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Write port of the decode-stage register file: the middle commits one register per instruction. */
class ArfWrite(width: Int) extends Bundle:
  val rd_index = Aligned(UInt(Gpr.indexWidth))
  val data = Aligned(UInt(width))

/** What decode hands the middle: the decode result plus the operands, already read from the register file and already
  * selected into the three slots the issue uop carries.
  *
  * The read and the selection sit in decode because nothing renames registers yet and the middle's idle gate already
  * guarantees the read happens after the previous commit's write-back. Adding renaming moves both into a middle stage,
  * and this payload reverts to index form.
  */
class IduOut(config: CoreConfig) extends Bundle:
  val pc = Aligned(UInt(config.width))
  val imm = Aligned(UInt(config.width))
  val rd_index = Aligned(UInt(Gpr.indexWidth))
  val src_a = Aligned(UInt(config.width))
  val src_b = Aligned(UInt(config.width))
  val src_c = Aligned(UInt(config.width))
  val backend = Aligned(UInt(Backend.width))
  val op = Aligned(UInt(OpWindow.width(config)))
  val csr_addr = Aligned(UInt(12))

case class IduParameter(isa: String, defaultPc: Long) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IduParameter] = upickle.default.macroRW

class IduLayers(parameter: IduParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IduIO(parameter: IduParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val in = Flipped(Decoupled(new FetchedInst(parameter.config.width)))
  val out = Aligned(Decoupled(new IduOut(parameter.config)))
  val arf_write = Flipped(ValidIO(new ArfWrite(parameter.config.width)))

class IduProbe(parameter: IduParameter) extends DVBundle[IduParameter, IduLayers](parameter)

/** Decode: one minimized PLA over the instruction word (`DecodeTables`) plus the architectural register file.
  *
  * Combinational stage: decode and the register read produce `IduOut` in the same cycle, and the pipeline registers
  * around it are owned by the front-end.
  */
@generator
object Idu extends Generator[IduParameter, IduLayers, IduIO, IduProbe]:
  override def moduleName(parameter: IduParameter): String = "Idu"

  def architecture(parameter: IduParameter) =
    val io = summon[Interface[IduIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val inst: Node[Bits] = Node(io.in.bits.inst.asBits)

    val decode = PlaPlan(DecodeTables.all(parameter.config))
    println(s"elaborating: ${decode.report}")
    val decoded = decode.decode(inst)
    val backend = decode.field(DecodeTables.BackendName, decoded)
    val op = decode.field(DecodeTables.OpName, decoded)
    val srcs = decode.field(DecodeTables.SrcsName, decoded)
    val immType = decode.field(DecodeTables.ImmTypeName, decoded)
    val gprWr = decode.flag(DecodeTables.GprWrName, decoded)

    // Both source indexes come straight from the instruction word, so the read does not wait on the PLA: which slot a
    // value lands in is the operand form's business, and the form resolves it below. The destination index stays gated
    // because it is a write address: an instruction without a destination must not name one.
    val rs1Index: Node[UInt] = inst.bits(19, 15).asUInt
    val rs2Index: Node[UInt] = inst.bits(24, 20).asUInt
    val rdIndex = gprWr ? (inst.bits(11, 7).asUInt, 0.U(Gpr.indexWidth))

    // The immediate shapes, built from the instruction's scattered fields (`[31:25]` funct7, `[24:20]` rs2,
    // `[19:15]` rs1, `[14:12]` funct3, `[11:7]` rd).
    def bit(index: Int): Node[Bits] = inst.bit(index).asBits

    val immI = (fill(20, inst.bit(31)) ## inst.bits(31, 20)).asUInt
    val immS = (fill(20, inst.bit(31)) ## inst.bits(31, 25) ## inst.bits(11, 7)).asUInt
    val immB = (fill(19, inst.bit(31)) ## bit(31) ## bit(7) ## inst.bits(30, 25) ## inst.bits(11, 8) ## 0.B(1)).asUInt
    val immU = (inst.bits(31, 12) ## 0.B(12)).asUInt
    val immJ =
      (fill(11, inst.bit(31)) ## bit(31) ## inst.bits(19, 12) ## bit(20) ## inst.bits(30, 21) ## 0.B(1)).asUInt
    val immZ = (0.B(27) ## inst.bits(19, 15)).asUInt
    val imm = mux1H(immType, Seq(immI, immS, immB, immU, immJ, immZ), width)

    val gprs = Reg(Vec(Gpr.depth, UInt(width)))

    // x0 is never written and reads as zero; every other register starts defined so the four-state sim sees the same
    // reset state as the reference model.
    when(!io.resetN.asBool) {
      for index <- 0 until Gpr.depth do gprs(index) := 0.U(width)
    }

    when(io.arf_write.valid & (io.arf_write.bits.rd_index =/= 0.U(Gpr.indexWidth))) {
      for index <- 0 until Gpr.depth do
        when(io.arf_write.bits.rd_index === index.U(Gpr.indexWidth)) { gprs(index) := io.arf_write.bits.data }
    }

    val rs1Val = gprs.ref(rs1Index)
    val rs2Val = gprs.ref(rs2Index)

    // The operand form says which architectural source feeds each uop slot; resolving it here is what keeps the source
    // selection out of the backend's execute cycle.
    val (srcA, srcB, srcC) = Srcs.resolve(srcs, rs1Val, rs2Val, imm, io.in.bits.pc, width)

    io.in.ready := io.out.ready
    io.out.valid := io.in.valid
    io.out.bits.pc := io.in.bits.pc
    io.out.bits.imm := imm
    io.out.bits.rd_index := rdIndex
    io.out.bits.src_a := srcA
    io.out.bits.src_b := srcB
    io.out.bits.src_c := srcC
    io.out.bits.backend := backend
    io.out.bits.op := op
    io.out.bits.csr_addr := inst.bits(31, 20).asUInt
