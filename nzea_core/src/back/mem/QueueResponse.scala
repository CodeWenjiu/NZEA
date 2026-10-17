package nzea_core.back.mem

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** The bus answer as its consumers see it: the tag that names the request, and the bus word. */
class QueueResponse(width: Int) extends Bundle:
    val tag = Aligned(UInt(DbusUser.tagWidth))
    val data = Aligned(UInt(width))
