package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BpuLevel, CoreConfig}
import nzea_core.back.{BpUpdate, IssueSlot, IssueUop, PathResult}
import nzea_core.isa.{CsrType, Isa}
import nzea_core.stats.Regions
import nzea_rtl.{RtlFlow, StatScope}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IntPathParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, stats: StatScope, bpu: BpuLevel)
    extends Parameter:
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

  /** The resolved branches, for the front's predictor: the branch unit's own wires, registered at the resolve cycle so
    * the tables' write decode hangs off registers rather than off the units' cones.
    */
  val bp_update = Aligned(ValidIO(new BpUpdate(width)))

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

    // The branch path's fall-through: the instruction's own `pc + 4`, carried from the fetch beside the prediction —
    // the lookup that answered the prediction made it too, so nothing here rebuilds that adder. It is the not-taken
    // continuation, the link value, and the value the write-back stage's prediction check compares against.
    val fallThrough: Referable[UInt] = u.fall_through

    /** The cycle a branch resolves: a per-instruction event on the uop's first cycle — the combinational unit holds its
      * uop for the whole residence, so counting the level would count one branch once per cycle it is held. A named
      * wire on purpose: the counters, the predictor's training and the waveform's event file (`branch.pulse` beside
      * this one) all address this event by name.
      */
    val branchResolved = Wire(Bool())
    branchResolved := uopFirst & isBru

    /** The three combinational units. The operands are the uop's slots — decode already resolved them — and each unit
      * slices its own width out of the shared op field.
      */
    def aluUnit() =
      val alu = Alu.instantiate(AluParameter(parameter.config.isa, parameter.config.defaultPc))
      alu.io.op := Encoding.take(fuOp, AluOp.set(parameter.config.isa).width)
      alu.io.opA := u.src_a
      alu.io.opB := u.src_b
      alu

    def bruUnit() =
      val bru = Bru.instantiate(BruParameter(width, parameter.bpu))
      bru.io.op := Encoding.take(fuOp, BruOp.width)
      bru.io.pc := u.pc
      // The prediction is data, and it has been since the fetch: the uop carries the next pc the fetcher predicted
      // when it read this instruction. A predictor that lands later replaces the fetcher's one value, not this unit.
      bru.io.predNextPc := u.next_pc
      bru.io.fallThrough := fallThrough
      bru.io.offset := u.imm
      bru.io.rs1 := u.src_a
      bru.io.rs2 := u.src_b
      bru

    def sysuUnit() =
      val sysu = Sysu.instantiate(SysuParameter(width))
      sysu.io.op := Encoding.take(fuOp, SysuOp.width)
      sysu.io.rs1 := u.src_a
      sysu.io.csrRdata := u.csr_rdata
      sysu.io.csrType := u.csr_type
      sysu

    val alu = aluUnit()
    val bru = bruUnit()
    val sysu = sysuUnit()

    // The resolved branch rides the result as data: `is_branch` marks it, and `next_pc` is wherever it actually goes —
    // under a predictor, a mispredict on a not-taken resolution points back at the fall-through. The prediction check
    // itself is the write-back stage's, made there against registers. The resolve cycle is a named wire on purpose
    // (like `branchResolved` below): its counters and the waveform's event file (`branch.pulse` beside this one) address
    // it by name, and a plain alias would fold into the unit's port.
    val branchMispred = Wire(Bool())
    branchMispred := uopValid & isBru & bru.io.mispredict

    /** MUL and DIV come and go with M, and the same set that decides that here decided the window's codes above. */
    def mulDivUnits() =
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
      (mul, div)

    val (mul, div) = mulDivUnits()
    val mulDone = mul.map(m => selects(IntOp.mul) & m.io.done).getOrElse(false.B)
    val divDone = div.map(d => selects(IntOp.div) & d.io.done).getOrElse(false.B)
    val mulData: Referable[UInt] = mul.map(m => m.io.data).getOrElse(0.U(width))
    val divData: Referable[UInt] = div.map(d => d.io.data).getOrElse(0.U(width))

    /** The result: every unit's value built and the tag picking between them, then the completion and the one-shot
      * result of the uop. A completion is a pulse per instruction: the combinational units report in the uop's first
      * cycle, the serial ones when they finish — a level would still be high when the next uop arrives, since the
      * middle admits it in the cycle this one commits.
      */
    def result(): Unit =
      // Every unit's value is built and the tag picks between them: the union owns that mux, and its entries are the
      // configured cases, so the two cannot drift apart. The order decides the shape only (the tags are exclusive):
      // the ALU's value is the deep one — a whole execute cone behind it — so it is registered last, at the mux's
      // shallow end, and the register-fed values take the depth instead.
      val unitData: IntOp => Referable[UInt] =
        case IntOp.Alu(_)  => alu.io.data
        case IntOp.Bru(_)  => fallThrough
        case IntOp.Sysu(_) => u.csr_rdata
        case IntOp.Mul(_)  => mulData
        case IntOp.Div(_)  => divData
      val (aluCases, otherCases) = ops.cases.partition { case IntOp.Alu(_) => true; case _ => false }
      val data: Referable[UInt] =
        ops.dispatch(op)((otherCases ++ aluCases).map(c => c -> ((_: Node[UInt]) => unitData(c)))*)
      val csrNone: Referable[UInt] = CsrType.None.lit.U(CsrType.width)

      io.done := uopValid & (uopFirst & (isAlu | isBru | isSysu) | mulDone | divDone)
      io.result.data := data
      // One mux, not two: `isBru & taken` picks the target in the same level the test lands, so the write-back stage's
      // next pc does not pay two mux stages for one choice.
      io.result.next_pc := (isBru & bru.io.taken) ? (bru.io.target, fallThrough)
      io.result.csr_type := isSysu ? (sysu.io.write ? (u.csr_type, csrNone), csrNone)
      io.result.csr_data := isSysu ? (sysu.io.write ? (sysu.io.data, 0.U(width)), 0.U(width))
      io.result.is_mmio := false.B
      io.result.is_branch := isBru
      io.result.pred_next_pc := u.next_pc

    result()

    /** The predictor's training data: the branch unit's own outcome, registered on the cycle it resolves. Every signal
      * already exists — the interface exists at every level, and nothing here knows whether a front is listening — and
      * the register is what keeps the tables' write decode (a whole PHT/BTB worth of fanout in the front) off the cones
      * that produce them, the target adder's above all. Training therefore lands one cycle after the resolve it
      * describes: a table reorganizes a round later, which only a self-loop tighter than roughly two cycles could
      * notice.
      */
    def bpTraining(): Unit =
      val updateValid = RegInit(false.B)
      val updatePc = Reg(UInt(width))
      val updateTaken = Reg(Bool())
      val updateTarget = Reg(UInt(width))
      val updateCount = Reg(UInt(BpUpdate.phtCountBits))
      when(branchResolved) {
        updatePc := u.pc
        updateTaken := bru.io.taken
        updateTarget := bru.io.target
        // The counter the fetch's prediction read, carried down the pipeline: the table's update writes the bumped value,
        // and it cannot read the old one back (a synchronous-read memory hands its read to the fetch, not to this path).
        updateCount := u.pht_count
      }
      updateValid := branchResolved
      io.bp_update.valid := updateValid
      io.bp_update.bits.pc := updatePc
      io.bp_update.bits.taken := updateTaken
      io.bp_update.bits.target := updateTarget
      io.bp_update.bits.pht_count := updateCount

    bpTraining()

    /** Counters the simulator reads back: every branch or jump that resolves here, how it went, and how the fetch
      * stream's prediction did against it. Declared here, and emitted from these declarations after elaboration.
      */
    def counters(): Unit =
      if parameter.flow.counters then
        val branch = parameter.stats.stat("bp_branch", Regions.Bp, "resolved branches")
        val taken = parameter.stats.stat("bp_taken", Regions.Bp, "resolved branches that were taken")
        val mispred = parameter.stats.stat("bp_mispred", Regions.Bp, "branches whose prediction was wrong")
        branch.inc(branchResolved)
        taken.inc(branchResolved & bru.io.taken)
        mispred.inc(branchResolved & branchMispred)
        parameter.stats.rule(
          "taken_rate",
          s"${taken.name} / ${branch.name} * 100",
          Regions.Bp,
          "%",
          2,
          "share of resolved branches that were taken, the bias a predictor exploits"
        )
        parameter.stats.rule(
          "mispredict_rate",
          s"${mispred.name} / ${branch.name} * 100",
          Regions.Bp,
          "%",
          2,
          "share of resolved branches predicted wrongly"
        )

    counters()
