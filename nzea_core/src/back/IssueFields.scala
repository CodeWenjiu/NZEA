package nzea_core.back

import nzea_core.back.int.IntOp
import nzea_core.back.mem.MemOp

/** Width of the issue uop's shared `op` field: the longest backend window.
  *
  * Whichever backend an instruction belongs to writes its whole decode result into this one field — unit select, op and
  * operand source included, laid out by that backend (`nzea_core.back.int.IntOp`, `nzea_core.back.mem.MemOp`). The
  * windows alias, because an instruction carries exactly one backend, and they are all low-anchored, so a wider field
  * only ever adds bits above the ones a backend already uses.
  */
object OpField:
  val Width: Int = Seq(IntOp.width, MemOp.width).max
