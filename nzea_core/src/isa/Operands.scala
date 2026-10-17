package nzea_core.isa

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Where one operand slot of the issue uop reads its value from. */
enum Operand:
    case Rs1, Rs2, Imm, Pc, Zero, Csr

/** The operand form of an instruction: which source feeds each of the uop's four operand slots.
  *
  * Slots are positional — `a`, `b`, `c`, `d` in the order the uop carries them — with [[Operand.Zero]] for the slots
  * the instruction leaves alone. The form is an ISA fact (which architectural operands an instruction has), so decode's
  * tables name it, and the decode stage resolves the slots once, before the middle latches them: a backend then reads
  * the slots its own op names instead of resolving sources in its execute cycle. The pool is four wide because a branch
  * is the widest instruction there is: its base, its offset and the two registers it compares.
  */
enum Srcs(val a: Operand, val b: Operand, val c: Operand, val d: Operand) extends Encoding.OneHot:
    case NoOperand extends Srcs(Operand.Zero, Operand.Zero, Operand.Zero, Operand.Zero)
    case Rs1Rs2 extends Srcs(Operand.Rs1, Operand.Rs2, Operand.Zero, Operand.Zero)
    case Rs1Imm extends Srcs(Operand.Rs1, Operand.Imm, Operand.Zero, Operand.Zero)
    case ImmZero extends Srcs(Operand.Imm, Operand.Zero, Operand.Zero, Operand.Zero)
    case PcImm extends Srcs(Operand.Pc, Operand.Imm, Operand.Zero, Operand.Zero)
    case Rs1ImmRs2 extends Srcs(Operand.Rs1, Operand.Imm, Operand.Rs2, Operand.Zero)

    /** A branch: the base rides the first slot like every other operand, so the branch unit's adder hangs off a placed
      * operand instead of a mux its own op decode selects.
      */
    case PcRs1Rs2 extends Srcs(Operand.Pc, Operand.Imm, Operand.Rs1, Operand.Rs2)

    /** A CSR instruction: the value the commit offers the file (a register or the immediate), and the value the read
      * settled on — the CSR's old value, placed like any other operand.
      */
    case Rs1Csr extends Srcs(Operand.Rs1, Operand.Csr, Operand.Zero, Operand.Zero)
    case ImmCsr extends Srcs(Operand.Imm, Operand.Csr, Operand.Zero, Operand.Zero)

object Srcs extends OneHotSet[Srcs]:

    /** The pool's arity: the operand pool ([[nzea_core.back.ExecSlot]]) is as wide as this, so the form's shape and the
      * storage cannot drift.
      */
    type Arity = 4

    def variants: Seq[Srcs] = values.toSeq

    /** Resolve the four slot values from a per-source table, one one-hot mux per slot. Callers pass the table once for
      * the values and once for the register indexes the slots were read from — the middle uses the second to tell which
      * slot a bypassed value belongs in.
      */
    def resolve(
        field: Referable[UInt],
        sources: Seq[(Operand, Referable[UInt])],
        width: Int
    )(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): (Node[UInt], Node[UInt], Node[UInt], Node[UInt]) =
        val table = sources.toMap
        def value(source: Operand): Referable[UInt] =
            table.getOrElse(source, throw new IllegalArgumentException(s"no source declared for $source"))
        def slot(pick: Srcs => Operand): Node[UInt] = mux1H(field, form => value(pick(form)), width)
        (slot(_.a), slot(_.b), slot(_.c), slot(_.d))
