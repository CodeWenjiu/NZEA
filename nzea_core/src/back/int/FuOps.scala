package nzea_core.back.int

import nzea_config.core.IsaConfig
import nzea_rtl.stdlib.*

/** Op encodings of the integer path's FUs. Decode places one of them in the uop's shared `op` field, the backend slices
  * it back out with `Encoding.take`, and the FU dispatches with `mux1H` over the raw field — which is why the one-hot
  * encodings below are load bearing.
  *
  * Each set answers with the configuration ([set]): the members an ISA adds are declared after the ones it shares, so a
  * member keeps its code whether or not it exists and the field only widens.
  */

/** ALU op: one-hot (add, sub, and, or, xor, sll, srl, sra, slt, sltu), then RV64's word forms, which are the same ops
  * on the low word with the result sign-extended.
  */
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
  case Addw
  case Subw
  case Sllw
  case Srlw
  case Sraw

  def exists(isa: IsaConfig): Boolean = this match
    case Add | Sub | And | Or | Xor | Sll | Srl | Sra | Slt | Sltu => true
    case Addw | Subw | Sllw | Srlw | Sraw                          => isa.xlen == 64

object AluOp:
  /** The base ops, then the word block: the ALU decodes its groups by position, and the word block mirrors the
    * arithmetic and shift groups above [[baseWidth]].
    */
  private val base = Seq(Add, Sub, And, Or, Xor, Sll, Srl, Sra, Slt, Sltu)
  private val word = Seq(Addw, Subw, Sllw, Srlw, Sraw)

  /** Where the word block starts: the bits the base ops occupy, and the distance from a base group to its word twin. */
  val baseWidth: Int = Encoding.width(base)

  def set(isa: IsaConfig): OneHotSet[AluOp] = OneHotSet.of(base ++ word)(_.exists(isa))

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

/** MUL FU op: MUL, MULH, MULHSU, MULHU, then MULW, RV64's word form.
  *
  * Without M the set is empty: the unit is not built, so neither are its codes — and a case in the window that carries
  * this set is only there when the member it names is ([[IntOp.exists]]).
  */
enum MulOp extends Encoding.OneHot:
  case Mul
  case Mulh
  case Mulhsu
  case Mulhu
  case Mulw

  def exists(isa: IsaConfig): Boolean = this match
    case Mul | Mulh | Mulhsu | Mulhu => isa.hasM
    case Mulw                        => isa.hasM && isa.xlen == 64

object MulOp:
  def set(isa: IsaConfig): OneHotSet[MulOp] = OneHotSet.of(values.toSeq)(_.exists(isa))

/** DIV FU op: DIV, DIVU, REM, REMU, then RV64's four word forms, which divide the low word and sign-extend the result.
  * Gated like [[MulOp]]'s: no M, no set.
  */
enum DivOp extends Encoding.OneHot:
  case Div
  case Divu
  case Rem
  case Remu
  case Divw
  case Divuw
  case Remw
  case Remuw

  def exists(isa: IsaConfig): Boolean = this match
    case Div | Divu | Rem | Remu     => isa.hasM
    case Divw | Divuw | Remw | Remuw => isa.hasM && isa.xlen == 64

object DivOp:
  def set(isa: IsaConfig): OneHotSet[DivOp] = OneHotSet.of(values.toSeq)(_.exists(isa))

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
