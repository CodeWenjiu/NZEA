package nzea_core.back.int

import nzea_core.isa.Isa
import nzea_rtl.stdlib.*

/** The int path's window in the issue uop's shared `op` field: which unit takes the uop, and that unit's own op.
  *
  * One case per unit, each carrying that unit's op encoding; which of them the window actually carries is the
  * configuration's ([exists]/[set]), since a unit that is not built takes its tag code with it. The layout below the
  * cases — the payload column and the case (tag) column above it — is the union's own business
  * (`Encoding.UnionSet`), so this backend states neither an offset nor a width, and the payload column is as wide as
  * the widest unit's op set under the configuration ([childWidth]). Operands are not part of this window: the uop's
  * slots carry them already resolved.
  */
enum IntOp extends Encoding.Union[Isa]:
    case Alu(payload: AluOp) extends IntOp
    case Bru(payload: BruOp) extends IntOp
    case Mul(payload: MulOp) extends IntOp
    case Div(payload: DivOp) extends IntOp
    case Sysu(payload: SysuOp) extends IntOp

    /** Whether this configuration has this case — the unit *and* the member the case carries. A unit's op set can gate
      * its members on the ISA (the multiplier's word form), and [set] asks every declared case, so a unit added without
      * an answer does not go unnoticed: the compiler names the pattern the match would fail on.
      */
    def exists(isa: Isa): Boolean = this match
        case Alu(op) => AluOp.set(isa).contains(op)
        case Bru(_)  => true
        case Mul(op) => MulOp.set(isa).contains(op)
        case Div(op) => DivOp.set(isa).contains(op)
        case Sysu(_) => true

    def childWidth(isa: Isa): Int = this match
        case Alu(_)  => AluOp.set(isa).width
        case Bru(_)  => BruOp.width
        case Mul(_)  => MulOp.set(isa).width
        case Div(_)  => DivOp.set(isa).width
        case Sysu(_) => SysuOp.width

object IntOp:

    /** One representative per case, each an ungated member: the tag tests read the case, and [set] asks [IntOp.exists]
      * of the representative, so it must be a member the case has whenever the case is there at all.
      */
    val alu = Alu(AluOp.Add)
    val bru = Bru(BruOp.JAL)
    val mul = Mul(MulOp.Mul)
    val div = Div(DivOp.Div)
    val sysu = Sysu(SysuOp.ECALL)

    /** The units this configuration has, in declaration order: without M the multiplier and divider are never built, so
      * their codes go with them instead of leaving holes in the tag column.
      */
    def set(isa: Isa): Encoding.UnionSet[IntOp, Isa] =
        Encoding.UnionSet.of(isa)(Seq(alu, bru, mul, div, sysu))(_.exists(isa))
