package nzea_core.isa

import nzea_rtl.stdlib.*

/** Which backend executes a decoded instruction.
  *
  * ISA-level because decode produces it, not because the split is architectural: the backends are the routing paths of
  * this core (int today, mem next, float later), each with its own scheduler and its own opcode table. Only the int
  * path needs a unit select on top (it has several FUs); the memory path has a single unit, so its op alone is enough.
  *
  * The backends' op and source encodings therefore alias in the uop's shared fields: an instruction carries exactly one
  * backend, so its op only has to be as wide as the widest backend needs (`nzea_core.back.OpField`).
  */
enum Backend(val lit: Int) extends Opcode:
  case INT extends Backend(0)
  case MEM extends Backend(1)

object Backend:
  val width: Int = Opcode.width(values)
