package nzea_core.back

import nzea_core.config.CoreConfig
import nzea_core.back.int.IntOp
import nzea_core.back.mem.MemOp

/** Width of the issue uop's op window: the longest of the backend windows that alias in it.
  *
  * Whichever backend an instruction belongs to writes its whole decode result — its unit select and its op — into this
  * one field, laid out by that backend ([[nzea_core.back.int.IntOp]], [[nzea_core.back.mem.MemOp]]). The windows alias,
  * because an instruction carries exactly one backend, and they are all low-anchored, so a wider field only ever adds
  * bits above the ones a backend already uses. The width follows the configuration: a backend that is not built drops
  * its tag codes, and the field only has to cover the windows that exist.
  */
object OpWindow:

    def width(config: CoreConfig): Int =
        Seq(IntOp.set(config.isa).width, MemOp.set(config.isa).width).max
