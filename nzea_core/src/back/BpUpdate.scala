package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** What the branch unit resolved, for the front's predictor: the branch's own pc, whether it was taken, and the target
  * it resolved to.
  *
  * Born on the branch's resolution cycle — the first cycle the branch unit sees the uop, which is also the cycle the
  * outcome is architectural: in-order execution resolves nothing speculative (a flush hides the younger stages from the
  * back), so the predictor trains on outcomes that were really on the program's path. The direction table takes every
  * outcome; the target buffer takes the taken ones.
  *
  * The front consumes this only when its level builds tables; the port exists either way, and the wires are the branch
  * unit's own already-computed signals — nothing here knows which level is listening.
  */
class BpUpdate(width: Int) extends Bundle:
  val pc = Aligned(UInt(width))
  val taken = Aligned(Bool())
  val target = Aligned(UInt(width))
