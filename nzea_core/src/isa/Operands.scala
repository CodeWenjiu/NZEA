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
  case Rs1, Rs2, Imm, Pc, Zero

/** The operand form of an instruction: which source feeds each of the uop's three operand slots.
  *
  * Slots are positional — `a`, `b`, `c` in the order the uop carries them — and a form names one source per slot, with
  * [[Operand.Zero]] for the slots the instruction leaves alone. The form is an ISA fact (which architectural operands
  * an instruction has), so decode's tables name it, decode's PLA writes the field, and the decode stage resolves the
  * slots once, before the middle latches them: a backend then reads the slots its own op names instead of resolving
  * sources in its execute cycle.
  */
enum Srcs(val a: Operand, val b: Operand, val c: Operand) extends Encoding.OneHot:
  case NoOperand extends Srcs(Operand.Zero, Operand.Zero, Operand.Zero)
  case Rs1 extends Srcs(Operand.Rs1, Operand.Zero, Operand.Zero)
  case Rs1Rs2 extends Srcs(Operand.Rs1, Operand.Rs2, Operand.Zero)
  case Rs1Imm extends Srcs(Operand.Rs1, Operand.Imm, Operand.Zero)
  case ImmZero extends Srcs(Operand.Imm, Operand.Zero, Operand.Zero)
  case PcImm extends Srcs(Operand.Pc, Operand.Imm, Operand.Zero)
  case Rs1ImmRs2 extends Srcs(Operand.Rs1, Operand.Imm, Operand.Rs2)

object Srcs extends OneHotSet[Srcs]:
  def variants: Seq[Srcs] = values.toSeq

  /** Resolve the three slot values from a per-source table: each slot is one one-hot mux over the forms, picking what
    * the form names for that slot. Callers pass the table once for the values and once for the register indexes the
    * slots were read from — the middle uses the second to tell which slot a bypassed value belongs in.
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
  ): (Node[UInt], Node[UInt], Node[UInt]) =
    val table = sources.toMap
    def value(source: Operand): Referable[UInt] =
      table.getOrElse(source, throw new IllegalArgumentException(s"no source declared for $source"))
    def slot(pick: Srcs => Operand): Node[UInt] = mux1H(field, form => value(pick(form)), width)
    (slot(_.a), slot(_.b), slot(_.c))
