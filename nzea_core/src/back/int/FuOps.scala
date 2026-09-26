package nzea_core.back.int

import nzea_rtl.stdlib.*

/** Op encodings of the integer path's FUs. Decode places one of them in the uop's shared `op` field, the backend slices
  * it back out with `Encoding.take`, and the FU dispatches with `mux1H` over the raw field — which is why the one-hot
  * encodings below are load bearing.
  */

/** ALU op: one-hot (add, sub, and, or, xor, sll, srl, sra, slt, sltu). */
enum AluOp extends Encoding.OneHot:
  case Add
  case Sub
  case And
  case Or
  case Xor
  case Sll
  case Srl
  case Sra
  case Slt
  case Sltu

object AluOp:
  val width: Int = Encoding.width(values)

/** BRU op: one-hot (JAL, JALR, BEQ, BNE, BLT, BGE, BLTU, BGEU). */
enum BruOp extends Encoding.OneHot:
  case JAL
  case JALR
  case BEQ
  case BNE
  case BLT
  case BGE
  case BLTU
  case BGEU

object BruOp:
  val width: Int = Encoding.width(values)

/** MUL FU op: MUL, MULH, MULHSU, MULHU (M extension). */
enum MulOp extends Encoding.OneHot:
  case Mul
  case Mulh
  case Mulhsu
  case Mulhu

object MulOp:
  val width: Int = Encoding.width(values)

/** DIV FU op: DIV, DIVU, REM, REMU (M extension). */
enum DivOp extends Encoding.OneHot:
  case Div
  case Divu
  case Rem
  case Remu

object DivOp:
  val width: Int = Encoding.width(values)

/** SYSU op: CSR ops + ECALL/EBREAK/FENCE. Encoded from funct3 at decode time, not the funct3 value itself. */
enum SysuOp extends Encoding.Sequential:
  case CSRRW
  case CSRRS
  case CSRRC
  case CSRRWI
  case CSRRSI
  case CSRRCI
  case ECALL
  case EBREAK
  case FENCE

object SysuOp:
  val width: Int = Encoding.width(values)
