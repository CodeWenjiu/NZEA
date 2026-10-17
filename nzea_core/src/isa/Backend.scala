package nzea_core.isa

import nzea_rtl.stdlib.*

/** Which backend executes a decoded instruction.
  *
  * ISA-level because decode produces it, not because the split is architectural: the backends are this core's routing
  * paths (int today, mem next), each with its own scheduler and its own encodings. Their op and source encodings alias
  * in the uop's shared fields: an instruction carries exactly one backend, so its op only has to be as wide as the
  * widest backend needs (`nzea_core.back.OpWindow`).
  */
enum Backend extends Encoding.Sequential:
    case INT
    case MEM

object Backend:
    val width: Int = Encoding.width(values)
