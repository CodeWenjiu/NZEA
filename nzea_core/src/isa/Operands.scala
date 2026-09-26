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
  * [[Operand.Zero]] for the slots the instruction leaves alone. The form is an ISA fact (which architectural operands an
  * instruction has), so decode's tables name it, decode's PLA writes the field, and the decode stage resolves the slots
  * once, before the middle latches them: a backend then reads the slots its own op names instead of resolving sources in
  * its execute cycle.
  */
enum Srcs(val a: Operand, val b: Operand, val c: Operand, val lit: Int) extends Opcode:
  case NoOperand extends Srcs(Operand.Zero, Operand.Zero, Operand.Zero, 1 << 0)
  case Rs1 extends Srcs(Operand.Rs1, Operand.Zero, Operand.Zero, 1 << 1)
  case Rs1Rs2 extends Srcs(Operand.Rs1, Operand.Rs2, Operand.Zero, 1 << 2)
  case Rs1Imm extends Srcs(Operand.Rs1, Operand.Imm, Operand.Zero, 1 << 3)
  case ImmZero extends Srcs(Operand.Imm, Operand.Zero, Operand.Zero, 1 << 4)
  case PcImm extends Srcs(Operand.Pc, Operand.Imm, Operand.Zero, 1 << 5)
  case Rs1ImmRs2 extends Srcs(Operand.Rs1, Operand.Imm, Operand.Rs2, 1 << 6)

object Srcs:
  val width: Int = Opcode.width(values)

  /** Resolve the three slot values from the architectural sources: the form field selects one source per slot, so each
    * slot is one one-hot mux over the forms.
    */
  def resolve(
      field: Node[UInt],
      rs1: Referable[UInt],
      rs2: Referable[UInt],
      imm: Referable[UInt],
      pc: Referable[UInt],
      width: Int
  )(
      using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): (Node[UInt], Node[UInt], Node[UInt]) =
    def value(source: Operand): Referable[UInt] = source match
      case Operand.Rs1  => rs1
      case Operand.Rs2  => rs2
      case Operand.Imm  => imm
      case Operand.Pc   => pc
      case Operand.Zero => 0.U(width)
    def slot(pick: Srcs => Operand): Node[UInt] =
      mux1H(field, values.toSeq.map(form => value(pick(form))), width)
    (slot(_.a), slot(_.b), slot(_.c))
