package nzea_core.back.int

import nzea_rtl.stdlib.*

/** The int path's window in the issue uop's shared `op` field: which FU takes the uop, and that FU's own op.
  *
  * One case per unit, each carrying that unit's op encoding. The layout below the cases — the payload column and the
  * case (tag) column above it — is the union's own business (`Encoding.UnionSet`), so this backend states neither an
  * offset nor a width. Operands are not part of this window: the uop's slots carry them already resolved, and which
  * slot a unit reads is this backend's own wiring.
  */
enum IntOp(val childWidth: Int) extends Encoding.Union:
  case Alu(payload: AluOp) extends IntOp(AluOp.width)
  case Bru(payload: BruOp) extends IntOp(BruOp.width)
  case Mul(payload: MulOp) extends IntOp(MulOp.width)
  case Div(payload: DivOp) extends IntOp(DivOp.width)
  case Sysu(payload: SysuOp) extends IntOp(SysuOp.width)

object IntOp extends Encoding.UnionSet[IntOp]:

  /** One representative per case, for the tag tests: they read the case, never its payload. */
  val alu = Alu(AluOp.Add)
  val bru = Bru(BruOp.JAL)
  val mul = Mul(MulOp.Mul)
  val div = Div(DivOp.Div)
  val sysu = Sysu(SysuOp.ECALL)

  val cases: Seq[IntOp] = Seq(alu, bru, mul, div, sysu)
