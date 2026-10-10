package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuConfig, CoreConfig}
import nzea_core.back.{BpUpdate, IntFace}
import nzea_core.isa.{CsrType, Isa}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IntPathParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, stats: StatScope, bpu: Option[BpuConfig])
    extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IntPathParameter] = upickle.default.macroRW

class IntPathLayers(parameter: IntPathParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IntPathIO(parameter: IntPathParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The middle's face, flipped: the gated uop in, the completion report out. */
    val path = Flipped(new IntFace(parameter.config))

    /** The resolved branches, for the front's predictor: the branch unit's own wires, registered at the resolve cycle
      * so the tables' write decode hangs off registers rather than off the units' cones. The port follows the
      * predictor's config — with no predictor there is no one to train, so neither the port nor the registers behind it
      * exist.
      */
    val bp_update: Option[BundleField[ValidIO[BpUpdate]]] =
        Option.when(parameter.bpu.isDefined)(Aligned(ValidIO(new BpUpdate(width))))

class IntPathProbe(parameter: IntPathParameter) extends DVBundle[IntPathParameter, IntPathLayers](parameter)

/** Integer execution path: every int unit driven from the held uop, with the resolved branch riding the result (the
  * middle raises the flush event the front restarts from).
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
        val uopValid = io.path.issue.valid
        val uopFirst = io.path.issue.first
        val u = io.path.issue.bits

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

        /** The cycle a branch resolves: a per-instruction event on the uop's first cycle — the combinational unit holds
          * its uop for the whole residence, so counting the level would count one branch once per cycle it is held. A
          * named wire on purpose: the counters, the predictor's training and the waveform's event file (`branch.pulse`
          * beside this one) all address this event by name.
          */
        val branchResolved = Wire(Bool())
        branchResolved := uopFirst & isBru

        /** The three combinational units. The operands are the uop's slots — decode already resolved them — and each
          * unit slices its own width out of the shared op field.
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
            // The form already placed the target's base in the first slot and the compared registers behind it, so the
            // unit reads its operands where they lie instead of selecting a base out of its own op.
            bru.io.base := u.src_a
            // The prediction is data, and it has been since the fetch: the uop carries the next pc the fetcher predicted
            // when it read this instruction. A predictor that lands later replaces the fetcher's one value, not this unit.
            bru.io.predNextPc := u.next_pc
            bru.io.fallThrough := fallThrough
            bru.io.offset := u.imm
            bru.io.rs1 := u.src_b
            bru.io.rs2 := u.src_c
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

        // The resolved branch rides the result as data: `next_pc` is wherever it actually goes — under a predictor, a
        // mispredict on a not-taken resolution points back at the fall-through — and `mispredict` is the branch unit's
        // own check, the one bit the retire record carries. The resolve cycle is a named wire on purpose (like
        // `branchResolved` below): its counters and the waveform's event file (`branch.pulse` beside this one) address it
        // by name, and a plain alias would fold into the unit's port.
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
          * result of the uop. The completion is the handshake's valid, held until the slot's pace takes it: a
          * combinational unit is done for as long as its uop sits in the slot — the value is recomputed from that same
          * uop, so the level costs no register — and a serial unit's pulse is latched here while the uop stays. The
          * latch clears when a new uop arrives or the slot empties (a flush), so it belongs to one uop only, and no
          * unit has to know the slot's pace.
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

            // The serial units report a pulse; hold it for as long as the same uop stays in the slot. The hold belongs
            // to the uop that produced it: a new uop's first cycle ignores it, or the uop entering behind a divide would
            // be reported done in the cycle it arrives.
            val serialHeld = RegInit(false.B)
            when(uopFirst | !uopValid) { serialHeld := false.B }
            when(mulDone | divDone) { serialHeld := true.B }
            io.path.done := uopValid & (isAlu | isBru | isSysu | mulDone | divDone | (serialHeld & !uopFirst))
            io.path.result.data := data
            // One mux, not two: `isBru & taken` picks the target in the same level the test lands, so the result's next
            // pc does not pay two mux stages for one choice.
            io.path.result.next_pc := (isBru & bru.io.taken) ? (bru.io.target, fallThrough)
            io.path.result.csr_type := isSysu ? (sysu.io.write ? (u.csr_type, csrNone), csrNone)
            io.path.result.csr_data := isSysu ? (sysu.io.write ? (sysu.io.data, 0.U(width)), 0.U(width))
            io.path.result.is_mmio := false.B
            io.path.result.mispredict := branchMispred

        result()

        /** The sysu's offer to the readers behind it: the CSR the held uop leaves defined and the value it leaves there.
          * It rides the path's own face rather than the result, because the result is the commit's account — gated by
          * whether the commit stores at all — while a reader behind the uop takes the post-state either way, and it must
          * be there the cycle the uop is held.
          */
        def csrOffer(): Unit =
            io.path.csr_offer.valid := isSysu & sysu.io.leaves
            io.path.csr_offer.bits.csr_type := u.csr_type
            io.path.csr_offer.bits.data := sysu.io.data

        csrOffer()

        /** The predictor's training data: the branch unit's own outcome, registered on the cycle it resolves. The port
          * is the predictor's own — the same config that builds the front's tables builds this end — and every signal
          * already exists; the register is what keeps the tables' write decode (a whole PHT/BTB worth of fanout in the
          * front) off the cones that produce them, the target adder's above all. Training therefore lands one cycle
          * after the resolve it describes: a table reorganizes a round later, which only a self-loop tighter than
          * roughly two cycles could notice.
          */
        def bpTraining(): Unit =
            if parameter.bpu.isDefined then
                val update = io.bp_update.get
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
                update.valid := updateValid
                update.bits.pc := updatePc
                update.bits.taken := updateTaken
                update.bits.target := updateTarget
                update.bits.pht_count := updateCount

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
                    "accuracy",
                    s"(${branch.name} - ${mispred.name}) / ${branch.name} * 100",
                    Regions.Bp,
                    "%",
                    2,
                    "share of resolved branches predicted correctly"
                )

        counters()
