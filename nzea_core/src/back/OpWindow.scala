package nzea_core.back

import nzea_core.back.int.IntOp
import nzea_core.back.mem.MemOp

/** Width of the issue uop's op window: the longest of the backend windows that alias in it.
  *
  * Whichever backend an instruction belongs to writes its whole decode result into this one field — its unit select and
  * its op — laid out by that backend (`nzea_core.back.int.IntOp`, `nzea_core.back.mem.MemOp`). The windows alias,
  * because an instruction carries exactly one backend, and they are all low-anchored, so a wider field only ever adds
  * bits above the ones a backend already uses. Operands are not part of any window: they travel resolved in the uop's
  * slots.
  */
object OpWindow:
  val Width: Int = Seq(IntOp.width, MemOp.width).max
