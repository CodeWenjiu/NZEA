package nzea_core.isa

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Per-FU operation enums and the unified `fu_op` field width.
  *
  * ISA-level: the decode table encodes them into the issue uop, each FU slices its own field with [[FuDecode.take]].
  * The one-hot encodings are load bearing — the FUs dispatch with `mux1H` over the raw field.
  */

/** ALU op: one-hot (add, sub, and, or, xor, sll, srl, sra, slt, sltu). */
enum AluOp(val lit: Int) extends Opcode:
  case Add extends AluOp(1 << 0)
  case Sub extends AluOp(1 << 1)
  case And extends AluOp(1 << 2)
  case Or extends AluOp(1 << 3)
  case Xor extends AluOp(1 << 4)
  case Sll extends AluOp(1 << 5)
  case Srl extends AluOp(1 << 6)
  case Sra extends AluOp(1 << 7)
  case Slt extends AluOp(1 << 8)
  case Sltu extends AluOp(1 << 9)

object AluOp:
  val width: Int = Opcode.width(values)

/** BRU op: one-hot (JAL, JALR, BEQ, BNE, BLT, BGE, BLTU, BGEU). */
enum BruOp(val lit: Int) extends Opcode:
  case JAL extends BruOp(1 << 0)
  case JALR extends BruOp(1 << 1)
  case BEQ extends BruOp(1 << 2)
  case BNE extends BruOp(1 << 3)
  case BLT extends BruOp(1 << 4)
  case BGE extends BruOp(1 << 5)
  case BLTU extends BruOp(1 << 6)
  case BGEU extends BruOp(1 << 7)

object BruOp:
  val width: Int = Opcode.width(values)

/** LSU op: one-hot (LB, LH, LW, LBU, LHU, SB, SH, SW); used by decode, the LSU and the bus user payload. */
enum LsuOp(val lit: Int) extends Opcode:
  case LB extends LsuOp(1 << 0)
  case LH extends LsuOp(1 << 1)
  case LW extends LsuOp(1 << 2)
  case LBU extends LsuOp(1 << 3)
  case LHU extends LsuOp(1 << 4)
  case SB extends LsuOp(1 << 5)
  case SH extends LsuOp(1 << 6)
  case SW extends LsuOp(1 << 7)

object LsuOp:
  val width: Int = Opcode.width(values)

  /** True for the store ops; a field that selects no store is treated as a load, like the legacy decoder. */
  def isStore[LHS <: Referable[UInt]](op: LHS)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bool] =
    (op === LsuOp.SB.lit.U(width)) | (op === LsuOp.SH.lit.U(width)) | (op === LsuOp.SW.lit.U(width))

  def isLoad[LHS <: Referable[UInt]](op: LHS)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bool] = !isStore(op)

/** MUL FU op: MUL, MULH, MULHSU, MULHU (M extension). */
enum MulOp(val lit: Int) extends Opcode:
  case Mul extends MulOp(1 << 0)
  case Mulh extends MulOp(1 << 1)
  case Mulhsu extends MulOp(1 << 2)
  case Mulhu extends MulOp(1 << 3)

object MulOp:
  val width: Int = Opcode.width(values)

/** DIV FU op: DIV, DIVU, REM, REMU (M extension). */
enum DivOp(val lit: Int) extends Opcode:
  case Div extends DivOp(1 << 0)
  case Divu extends DivOp(1 << 1)
  case Rem extends DivOp(1 << 2)
  case Remu extends DivOp(1 << 3)

object DivOp:
  val width: Int = Opcode.width(values)

/** SYSU op: CSR ops + ECALL/EBREAK/FENCE. Encoded from funct3 at decode time, not the funct3 value itself. */
enum SysuOp(val lit: Int) extends Opcode:
  case CSRRW extends SysuOp(0)
  case CSRRS extends SysuOp(1)
  case CSRRC extends SysuOp(2)
  case CSRRWI extends SysuOp(3)
  case CSRRSI extends SysuOp(4)
  case CSRRCI extends SysuOp(5)
  case ECALL extends SysuOp(6)
  case EBREAK extends SysuOp(7)
  case FENCE extends SysuOp(8)

object SysuOp:
  val width: Int = Opcode.width(values)

/** Unified `fu_op` field width: the widest FU op encoding, so one field can feed every FU. */
object FuOpWidth:
  val Width: Int = Seq(AluOp.width, BruOp.width, LsuOp.width, MulOp.width, DivOp.width, SysuOp.width).max
