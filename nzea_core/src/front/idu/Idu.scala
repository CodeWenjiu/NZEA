package nzea_core.front.idu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, DecoupledIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.back.OpWindow
import nzea_core.front.ifu.FetchedInst
import nzea_core.isa.{Backend, Gpr, Isa, Srcs}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What decode hands the middle: the decoded instruction — where it is, the pc the fetch predicted it goes on to and
  * the instruction's own fall-through, its immediate, the register it writes, the two registers it can read, and the
  * operand form that says which source fills each issue slot.
  *
  * The values are not here. Decode stays a decode and the read happens in the middle, in the cycle the uop is issued:
  * that is the first cycle the register file holds every write the instruction must see, so the read never needs the
  * fix-up a read a cycle earlier would.
  */
class IduOut(config: CoreConfig) extends Bundle:
  val pc = Aligned(UInt(config.width))
  val next_pc = Aligned(UInt(config.width))
  val fall_through = Aligned(UInt(config.width))
  val imm = Aligned(UInt(config.width))
  val rd_index = Aligned(UInt(Gpr.indexWidth))
  val rs1_index = Aligned(UInt(Gpr.indexWidth))
  val rs2_index = Aligned(UInt(Gpr.indexWidth))
  val srcs = Aligned(UInt(Srcs.width))
  val backend = Aligned(UInt(Backend.width))
  val op = Aligned(UInt(OpWindow.width(config)))
  val csr_addr = Aligned(UInt(12))

case class IduParameter(isa: Isa, defaultPc: Long) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IduParameter] = upickle.default.macroRW

class IduLayers(parameter: IduParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IduIO(parameter: IduParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val in = Flipped(Decoupled(new FetchedInst(parameter.config.width)))
  val out = Aligned(Decoupled(new IduOut(parameter.config)))

class IduProbe(parameter: IduParameter) extends DVBundle[IduParameter, IduLayers](parameter)

/** Decode: one minimized PLA over the instruction word (`DecodeTables`) and the immediate shapes.
  *
  * Combinational stage: decode produces `IduOut` in the same cycle the instruction arrives, and the pipeline registers
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

    // Both source indexes come straight from the instruction word, so nothing here waits on the PLA: which slot a value
    // lands in is the operand form's business, and the form travels beside them. The destination index stays gated
    // because it is a write address: an instruction without a destination must not name one.
    val rs1Index: Node[UInt] = inst.bits(19, 15).asUInt
    val rs2Index: Node[UInt] = inst.bits(24, 20).asUInt
    val rdIndex = gprWr ? (inst.bits(11, 7).asUInt, 0.U(Gpr.indexWidth))

    // The immediate shapes, built from the instruction's scattered fields (`[31:25]` funct7, `[24:20]` rs2,
    // `[19:15]` rs1, `[14:12]` funct3, `[11:7]` rd). The field is 32 bits in both ISAs but the value it names is xlen
    // wide, so every shape sign-extends bit 31 up to the register width; `Z` is the one shape that zero-extends.
    def bit(index: Int): Node[Bits] = inst.bit(index).asBits

    val immI = (fill(width - 12, inst.bit(31)) ## inst.bits(31, 20)).asUInt
    val immS = (fill(width - 12, inst.bit(31)) ## inst.bits(31, 25) ## inst.bits(11, 7)).asUInt
    val immB =
      (fill(width - 13, inst.bit(31)) ## bit(31) ## bit(7) ## inst.bits(30, 25) ## inst.bits(11, 8) ## 0.B(1)).asUInt
    // U's immediate is 32 bits of its own (bits 31:12 and zeros), signed by bit 31 like the rest.
    val immU =
      if width > 32 then (fill(width - 32, inst.bit(31)) ## inst.bits(31, 12) ## 0.B(12)).asUInt
      else (inst.bits(31, 12) ## 0.B(12)).asUInt
    val immJ =
      (fill(width - 21, inst.bit(31)) ## bit(31) ## inst.bits(19, 12) ## bit(20) ## inst.bits(30, 21) ## 0.B(1)).asUInt
    val immZ = (0.B(width - 5) ## inst.bits(19, 15)).asUInt
    val imm = mux1H(immType, Seq(immI, immS, immB, immU, immJ, immZ), width)

    io.in.ready := io.out.ready
    io.out.valid := io.in.valid
    io.out.bits.pc := io.in.bits.pc
    io.out.bits.next_pc := io.in.bits.next_pc
    io.out.bits.fall_through := io.in.bits.fall_through
    io.out.bits.imm := imm
    io.out.bits.rd_index := rdIndex
    io.out.bits.rs1_index := rs1Index
    io.out.bits.rs2_index := rs2Index
    io.out.bits.srcs := srcs
    io.out.bits.backend := backend
    io.out.bits.op := op
    io.out.bits.csr_addr := inst.bits(31, 20).asUInt
