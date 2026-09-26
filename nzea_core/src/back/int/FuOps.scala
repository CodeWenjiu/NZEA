package nzea_core.back.int

import nzea_rtl.stdlib.*

/** Op encodings of the integer path's FUs. Decode places one of them in the uop's shared `op` field, the backend slices
  * it back out with `Opcode.take`, and the FU dispatches with `mux1H` over the raw field — which is why the one-hot
  * encodings below are load bearing.
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
