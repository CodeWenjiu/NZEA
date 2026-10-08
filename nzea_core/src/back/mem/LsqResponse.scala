package nzea_core.back.mem

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** The bus answer as its consumers see it: the tag that names the request, and the bus word.
  *
  * The memory path produces it from the bus's response channel and the completion path's queue consumes it to complete
  * the entry whose tag it carries — both ends of the same signal, so it is its own type rather than one side's field.
  */
class LsqResponse(width: Int) extends Bundle:
    val tag = Aligned(UInt(DbusUser.tagWidth))
    val data = Aligned(UInt(width))
