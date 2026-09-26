package nzea_core.isa

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Function unit type for routing (ALU/BRU/LSU/MUL/DIV/SYSU).
  *
  * ISA-level: the decode table produces it, the middle dispatches on it, the backend routes by it.
  */
enum FuType(val lit: Int) extends Opcode:
  case ALU extends FuType(0)
  case BRU extends FuType(1)
  case LSU extends FuType(2)
  case MUL extends FuType(3)
  case DIV extends FuType(4)
  case SYSU extends FuType(5)

object FuType:
  val width: Int = Opcode.width(values)

/** ALU operand source for opA/opB; one-hot (Rs1Rs2, Rs1Imm, ImmZero, PcImm). */
enum AluSrc(val lit: Int) extends Opcode:
  case Rs1Rs2 extends AluSrc(1 << 0)
  case Rs1Imm extends AluSrc(1 << 1)
  case ImmZero extends AluSrc(1 << 2)
  case PcImm extends AluSrc(1 << 3)

object AluSrc:
  val width: Int = Opcode.width(values)

/** BRU target source: one-hot (PcImm, Rs1Imm). */
enum BruSrc(val lit: Int) extends Opcode:
  case PcImm extends BruSrc(1 << 0)
  case Rs1Imm extends BruSrc(1 << 1)

object BruSrc:
  val width: Int = Opcode.width(values)

/** LSU address source: one-hot (Rs1Imm). */
enum LsuSrc(val lit: Int) extends Opcode:
  case Rs1Imm extends LsuSrc(1 << 0)

object LsuSrc:
  val width: Int = Opcode.width(values)

/** `fu_src` field width: the widest FU source encoding. Update this and the enums above when adding an FU source. */
object FuSrcWidth:
  val Width: Int = Seq(AluSrc.width, BruSrc.width, LsuSrc.width).max

/** Slice the unified `fu_src`/`fu_op` field to the width of one FU's own encoding, so no consumer hand-writes a bit
  * range.
  */
object FuDecode:

  def take[LHS <: Referable[UInt]](u: LHS, width: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] =
    val bits: Node[Bits] = Node(u.asBits)
    bits.bits(width - 1, 0).asUInt
