package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.back.{IssueSlot, IssueUop, PathResult}
import nzea_core.isa.{CsrType, Isa}
import nzea_core.stats.Regions
import nzea_rtl.{RtlFlow, StatScope}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IntPathParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, stats: StatScope) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IntPathParameter] = upickle.default.macroRW

class IntPathLayers(parameter: IntPathParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IntPathIO(parameter: IntPathParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(new IssueSlot(new IssueUop(parameter.config)))
  val done = Aligned(Bool())
  val result = Aligned(new PathResult(width))

class IntPathProbe(parameter: IntPathParameter) extends DVBundle[IntPathParameter, IntPathLayers](parameter)

/** Integer execution path: every int unit driven from the held uop, with the resolved branch riding the result (the
  * middle forwards the redirect to the front).
  *
  * This is where the unit select and the op mean something — decode only packed them into [[IntOp]]'s columns. Each
  * unit owns its own op encoding and its own operand use, so all this path does is slice the shared field per unit,
  * merge the results by tag, and gate the completion: ALU, BRU and SYSU are combinational and report completion for as
  * long as the uop is held, MUL and DIV are multi-cycle units that report it from their FSM.
  */
@generator
object IntPath extends Generator[IntPathParameter, IntPathLayers, IntPathIO, IntPathProbe]:
  override def moduleName(parameter: IntPathParameter): String = "IntPath"

  /** The block name the int path's statistics are attributed to; the parent's scope takes it as a segment. */
  val Source: String = "int"

  def architecture(parameter: IntPathParameter) =
    val io = summon[Interface[IntPathIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val uopValid = io.issue.valid
    val uopFirst = io.issue.first
    val u = io.issue.bits

    val ops = IntOp.set(parameter.config.isa)
    val op: Referable[UInt] = u.op
    val fuOp: Node[UInt] = ops.payload(op)

    def selects(caseOf: IntOp): Node[Bool] = ops.isCase(caseOf, op)
    val isAlu = selects(IntOp.alu)
    val isBru = selects(IntOp.bru)
    val isSysu = selects(IntOp.sysu)

    // The operands are the uop's slots: decode already resolved them, so a unit reads the slot its form filled. The op
    // field each unit slices is as wide as that unit's op set under this configuration.
    val alu = Alu.instantiate(AluParameter(parameter.config.isa, parameter.config.defaultPc))
    alu.io.op := Encoding.take(fuOp, AluOp.set(parameter.config.isa).width)
    alu.io.opA := u.src_a
    alu.io.opB := u.src_b

    val bru = Bru.instantiate(BruParameter(width))
    bru.io.op := Encoding.take(fuOp, BruOp.width)
    bru.io.pc := u.pc
    // The prediction is data: the baseline predicts the fall-through, and a predictor that lands later replaces this
    // line rather than the unit. The fall-through itself is a uop field — the middle resolved it where the pc is a held
    // value, which keeps this cycle's adder count at one.
    bru.io.predNextPc := u.pc_plus4
    bru.io.offset := u.imm
    bru.io.rs1 := u.src_a
    bru.io.rs2 := u.src_b

    val sysu = Sysu.instantiate(SysuParameter(width))
    sysu.io.op := Encoding.take(fuOp, SysuOp.width)
    sysu.io.rs1 := u.src_a
    sysu.io.csrRdata := u.csr_rdata
    sysu.io.csrType := u.csr_type

    // The resolved branch rides the result: `redirect_valid` is the mispredict, and the target — not `nextPc` — is
    // where the fetch stream restarts (the middle latches both with the completion and broadcasts them in write-back).
    // The condition is a named wire on purpose (like `branchResolved` below): the redirect and the waveform's event
    // file (`branch.pulse` beside this one) both address it by name, and a plain alias would fold into the unit's port.
    val branchMispred = Wire(Bool())
    branchMispred := uopValid & isBru & bru.io.mispredict
    io.result.redirect_valid := branchMispred
    io.result.redirect_target := bru.io.target

    // MUL and DIV come and go with M, and the same set that decides that here decided the window's codes above.
    val mul = Option.when(ops.contains(IntOp.mul))(
      Mul.instantiate(MulParameter(parameter.config.isa, parameter.config.defaultPc))
    )
    val div = Option.when(ops.contains(IntOp.div))(
      Div.instantiate(DivParameter(parameter.config.isa, parameter.config.defaultPc))
    )
    for m <- mul do
      m.io.clock := io.clock
      m.io.resetN := io.resetN
      m.io.issue.valid := uopValid & selects(IntOp.mul)
      m.io.issue.first := uopFirst
      m.io.issue.bits.opA := u.src_a
      m.io.issue.bits.opB := u.src_b
      m.io.issue.bits.op := Encoding.take(fuOp, MulOp.set(parameter.config.isa).width)
    for d <- div do
      d.io.clock := io.clock
      d.io.resetN := io.resetN
      d.io.issue.valid := uopValid & selects(IntOp.div)
      d.io.issue.first := uopFirst
      d.io.issue.bits.opA := u.src_a
      d.io.issue.bits.opB := u.src_b
      d.io.issue.bits.op := Encoding.take(fuOp, DivOp.set(parameter.config.isa).width)
    val mulDone = mul.map(m => selects(IntOp.mul) & m.io.done).getOrElse(false.B)
    val divDone = div.map(d => selects(IntOp.div) & d.io.done).getOrElse(false.B)
    val mulData: Referable[UInt] = mul.map(m => m.io.data).getOrElse(0.U(width))
    val divData: Referable[UInt] = div.map(d => d.io.data).getOrElse(0.U(width))

    // Every unit's value is built and the tag picks between them: the union owns that mux, and its entries are the
    // configured cases in tag order, so the two cannot drift apart.
    val unitData: IntOp => Referable[UInt] =
      case IntOp.Alu(_)  => alu.io.data
      case IntOp.Bru(_)  => u.pc_plus4
      case IntOp.Sysu(_) => u.csr_rdata
      case IntOp.Mul(_)  => mulData
      case IntOp.Div(_)  => divData

    val data: Referable[UInt] = ops.dispatch(op)(ops.cases.map(c => c -> ((_: Node[UInt]) => unitData(c)))*)

    val csrNone: Referable[UInt] = CsrType.None.lit.U(CsrType.width)
    // One completion pulse per instruction: the combinational units report in the uop's first cycle, the serial ones
    // when they finish. A level would still be high when the next uop arrives — the middle admits it in the cycle this
    // one commits.
    io.done := uopValid & (uopFirst & (isAlu | isBru | isSysu) | mulDone | divDone)
    io.result.data := data
    io.result.next_pc := isBru ? (bru.io.nextPc, u.pc_plus4)
    io.result.csr_type := isSysu ? (sysu.io.write ? (u.csr_type, csrNone), csrNone)
    io.result.csr_data := isSysu ? (sysu.io.write ? (sysu.io.data, 0.U(width)), 0.U(width))
    io.result.is_mmio := false.B

    // Counters the simulator reads back: every branch or jump that resolves here, and how the fetch stream did against
    // it. Declared here, and emitted from these declarations after elaboration.
    if parameter.flow.counters then
      val branch = parameter.stats.stat("bp_branch", Regions.Bp, "resolved branches")
      val mispred = parameter.stats.stat("bp_mispred", Regions.Bp, "branches whose prediction was wrong")
      // A per-instruction event is the slot's first cycle: a combinational unit holds its uop for the whole residence,
      // so counting the level would count one branch once per cycle it is held — and with back-to-back instructions the
      // level does not fall between them. It is a named wire on purpose: the counters and the waveform's event file
      // (`branch.pulse` beside this one) both address this event by name, and a plain val would be inlined into an
      // anonymous net.
      val branchResolved = Wire(Bool())
      branchResolved := uopFirst & isBru
      branch.inc(branchResolved)
      mispred.inc(branchResolved & branchMispred)
      // No taken counter: the fetch stream predicts the fall-through, so a taken branch *is* a misprediction here and a
      // second counter would report the same number twice. The taken bias is what a predictor turns into a prediction —
      // it becomes a statistic of its own when one lands.
      parameter.stats.rule(
        "mispredict_rate",
        s"${mispred.name} / ${branch.name} * 100",
        Regions.Bp,
        "%",
        2,
        "share of resolved branches predicted wrongly"
      )
