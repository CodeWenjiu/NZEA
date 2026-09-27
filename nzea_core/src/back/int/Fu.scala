package nzea_core.back.int

import nzea_config.core.IsaConfig
import nzea_rtl.stdlib.*

/** The int path's window in the issue uop's shared `op` field: which unit takes the uop, and that unit's own op.
  *
  * One case per unit, each carrying that unit's op encoding; which of them the window actually carries is the
  * configuration's ([exists]/[set]), since a unit that is not built takes its tag code with it. The layout below the
  * cases — the payload column and the case (tag) column above it — is the union's own business (`Encoding.UnionSet`),
  * so this backend states neither an offset nor a width. Operands are not part of this window: the uop's slots carry
  * them already resolved, and which slot a unit reads is this backend's own wiring.
  */
enum IntOp(val childWidth: Int) extends Encoding.Union:
  case Alu(payload: AluOp) extends IntOp(AluOp.width)
  case Bru(payload: BruOp) extends IntOp(BruOp.width)
  case Mul(payload: MulOp) extends IntOp(MulOp.width)
  case Div(payload: DivOp) extends IntOp(DivOp.width)
  case Sysu(payload: SysuOp) extends IntOp(SysuOp.width)

  /** Whether this configuration has the unit — and, with it, the case's code. An enum case cannot carry a body in Scala
    * 3, so the answer is one match over the cases: a unit added without an answer does not go unnoticed, since the
    * compiler names the pattern the match would fail on and [set] asks every declared case.
    */
  def exists(isa: IsaConfig): Boolean = this match
    case Alu(_) | Bru(_) | Sysu(_) => true
    case Mul(_) | Div(_)           => isa.hasM

object IntOp:

  /** One representative per case: the tag tests and [set] read the case, never its payload. */
  val alu = Alu(AluOp.Add)
  val bru = Bru(BruOp.JAL)
  val mul = Mul(MulOp.Mul)
  val div = Div(DivOp.Div)
  val sysu = Sysu(SysuOp.ECALL)

  /** The units this configuration has, in declaration order: without M the multiplier and divider are never built, so
    * their codes go with them instead of leaving holes in the tag column.
    */
  def set(isa: IsaConfig): Encoding.UnionSet[IntOp] =
    Encoding.UnionSet.of(Seq(alu, bru, mul, div, sysu))(_.exists(isa))
