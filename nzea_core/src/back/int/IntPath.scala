package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.RtlFlow
import nzea_config.core.CoreConfig
import nzea_core.back.{IssueUop, PathResult}
import nzea_core.isa.CsrType
import nzea_rtl.StatsRegs
import nzea_rtl.stdlib.*
import nzea_core.stats.{BpCounters, BpCountersParameter}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IntPathParameter(isa: String, defaultPc: Long, flow: RtlFlow) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IntPathParameter] = upickle.default.macroRW

class IntPathLayers(parameter: IntPathParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IntPathIO(parameter: IntPathParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(Valid(new IssueUop(parameter.config)))
  val done = Aligned(Bool())
  val result = Aligned(new PathResult(width))
  val redirect = Aligned(Valid(UInt(width)))

class IntPathProbe(parameter: IntPathParameter) extends DVBundle[IntPathParameter, IntPathLayers](parameter)

/** Integer execution path: every int unit driven from the held uop, plus the branch redirect the middle forwards to the
  * front.
  *
  * This is where the unit select and the op mean something — decode only packed them into [[IntOp]]'s columns. Each
  * unit owns its own op encoding and its own operand use, so all this path does is slice the shared field per unit,
  * merge the results by tag, and gate the completion: ALU, BRU and SYSU are combinational and report completion for as
  * long as the uop is held, MUL and DIV are multi-cycle units that report it from their FSM.
  */
@generator
object IntPath extends Generator[IntPathParameter, IntPathLayers, IntPathIO, IntPathProbe]:
  override def moduleName(parameter: IntPathParameter): String = "IntPath"

  def architecture(parameter: IntPathParameter) =
    val io = summon[Interface[IntPathIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val uopValid = io.issue.valid
    val u = io.issue.bits

    val ops = IntOp.set(parameter.config.isaConfig)
    val op: Referable[UInt] = u.op
    val fuOp: Node[UInt] = ops.payload(op)

    def selects(caseOf: IntOp): Node[Bool] = ops.isCase(caseOf, op)
    val isAlu = selects(IntOp.alu)
    val isBru = selects(IntOp.bru)
    val isSysu = selects(IntOp.sysu)

    // The operands are the uop's slots: decode already resolved them, so a unit reads the slot its form filled.
    val alu = Alu.instantiate(AluParameter(width))
    alu.io.op := Encoding.take(fuOp, AluOp.width)
    alu.io.opA := u.src_a
    alu.io.opB := u.src_b

    val pcPlus4 = prefixAdder(u.pc, 4.U(width), false.B, width)

    val bru = Bru.instantiate(BruParameter(width))
    bru.io.op := Encoding.take(fuOp, BruOp.width)
    bru.io.pc := u.pc
    // The prediction is data: the baseline predicts the fall-through, and a predictor that lands later replaces this
    // line rather than the unit.
    bru.io.predNextPc := pcPlus4
    bru.io.offset := u.imm
    bru.io.rs1 := u.src_a
    bru.io.rs2 := u.src_b

    val sysu = Sysu.instantiate(SysuParameter(width))
    sysu.io.op := Encoding.take(fuOp, SysuOp.width)
    sysu.io.rs1 := u.src_a
    sysu.io.csrRdata := u.csr_rdata
    sysu.io.csrType := u.csr_type

    io.redirect.valid := uopValid & isBru & bru.io.mispredict
    // The target, not `nextPc`: the middle reads the redirect target only when it flushes, which means taken.
    io.redirect.bits := bru.io.target

    // MUL and DIV come and go with M, and the same set that decides that here decided the window's codes above.
    val mul = Option.when(ops.contains(IntOp.mul))(Mul.instantiate(MulParameter(width)))
    val div = Option.when(ops.contains(IntOp.div))(Div.instantiate(DivParameter(width)))
    for m <- mul do
      m.io.clock := io.clock
      m.io.resetN := io.resetN
      m.io.issue.valid := uopValid & selects(IntOp.mul)
      m.io.issue.bits.opA := u.src_a
      m.io.issue.bits.opB := u.src_b
      m.io.issue.bits.op := Encoding.take(fuOp, MulOp.width)
    for d <- div do
      d.io.clock := io.clock
      d.io.resetN := io.resetN
      d.io.issue.valid := uopValid & selects(IntOp.div)
      d.io.issue.bits.opA := u.src_a
      d.io.issue.bits.opB := u.src_b
      d.io.issue.bits.op := Encoding.take(fuOp, DivOp.width)
    val mulDone = mul.map(m => selects(IntOp.mul) & m.io.done).getOrElse(false.B)
    val divDone = div.map(d => selects(IntOp.div) & d.io.done).getOrElse(false.B)
    val mulData: Referable[UInt] = mul.map(m => m.io.data).getOrElse(0.U(width))
    val divData: Referable[UInt] = div.map(d => d.io.data).getOrElse(0.U(width))

    // Every unit's value is built and the tag picks between them: the union owns that mux, and its entries are the
    // configured cases in tag order, so the two cannot drift apart.
    val unitData: IntOp => Referable[UInt] =
      case IntOp.Alu(_)  => alu.io.data
      case IntOp.Bru(_)  => pcPlus4
      case IntOp.Sysu(_) => u.csr_rdata
      case IntOp.Mul(_)  => mulData
      case IntOp.Div(_)  => divData

    val data: Referable[UInt] = ops.dispatch(op)(ops.cases.map(c => c -> ((_: Node[UInt]) => unitData(c)))*)

    val csrNone: Referable[UInt] = CsrType.None.lit.U(CsrType.width)
    io.done := uopValid & (isAlu | isBru | isSysu | mulDone | divDone)
    io.result.data := data
    io.result.next_pc := isBru ? (bru.io.nextPc, pcPlus4)
    io.result.csr_type := isSysu ? (sysu.io.write ? (u.csr_type, csrNone), csrNone)
    io.result.csr_data := isSysu ? (sysu.io.write ? (sysu.io.data, 0.U(width)), 0.U(width))
    io.result.is_mmio := false.B

    // Counters the simulator reads back: every branch or jump that resolves here, and how the fetch stream did against
    // it.
    if parameter.flow.counters then
      val stats = BpCounters.instantiate(BpCountersParameter())
      stats.io.clock := io.clock
      stats.io.resetN := io.resetN.asBool
      // A combinational unit holds its uop for the whole residence, so a per-instruction event is the arrival edge —
      // counting the level would count one branch once per cycle it is held. It is a named wire on purpose: the
      // counters and the waveform's event file (`branch.pulse` beside this one) both address this event by name, and a
      // plain val would be inlined into an anonymous net.
      val branchResolved = Wire(Bool())
      branchResolved := uopValid & isBru & !regNext(uopValid, false.B)
      stats.io.stat_bp_branch.en := branchResolved
      stats.io.stat_bp_branch.data :=
        addWrap(stats.io.stat_bp_branch.value, 1.U(StatsRegs.Width), StatsRegs.Width)
      stats.io.stat_bp_mispred.en := branchResolved & bru.io.mispredict
      stats.io.stat_bp_mispred.data :=
        addWrap(stats.io.stat_bp_mispred.value, 1.U(StatsRegs.Width), StatsRegs.Width)
      stats.io.stat_bp_actual_taken.en := branchResolved & bru.io.taken
      stats.io.stat_bp_actual_taken.data :=
        addWrap(stats.io.stat_bp_actual_taken.value, 1.U(StatsRegs.Width), StatsRegs.Width)
