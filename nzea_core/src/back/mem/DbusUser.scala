package nzea_core.back.mem

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The data bus's `user` field: the request tag, carried on the request and echoed on the response. The tag is the
  * whole payload because the path already holds everything else a response needs — the op and the address offset come
  * from the uop.
  *
  * What the tag buys is telling the current access's response apart from a stale one: a store completes on its write's
  * acceptance, so its acknowledgment is still in flight while younger accesses are issued, and the fabric keeps order
  * per slave rather than per master — a younger access to a faster slave can be answered first. A response whose tag
  * is not the current uop's is therefore stale and drains. The width must outlast the worst-case response latency
  * measured in issued accesses, or a wrapped tag would let a stale response pass as current: the tile's RAM latency
  * is ~20 cycles with jitter and accesses can issue one per cycle, so 6 bits carry a comfortable margin over the ~30
  * accesses that could be in flight.
  */
object DbusUser:

    val tagWidth: Int = 6

    object Layout extends Encoding.Aggregate:
        val fields: Seq[(String, Int)] = Seq("tag" -> tagWidth)

    def userWidth: Int = Layout.width

    def pack[T <: Referable[UInt]](tag: T)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] = Node(tag.asBits).pad(userWidth).asUInt

    def tagOf[T <: Referable[UInt]](user: T)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] = Layout.read("tag", user)
