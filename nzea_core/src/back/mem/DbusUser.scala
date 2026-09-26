package nzea_core.back.mem

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The data bus's `user` field: the request tag, carried on the request and echoed on the response.
  *
  * The tag is the whole payload because the path already holds everything else a response needs (the op and the address
  * offset come from the uop, which the middle holds) and this engine keeps one request outstanding. What the tag buys
  * is telling a response for an abandoned request apart from the current one's: a flushed request's response drains and
  * is dropped instead of completing the next uop. It must outlast the worst-case response latency measured in issued
  * requests, or a wrapped tag would let a stale response pass as current.
  */
object DbusUser:

  val tagWidth: Int = 2

  val tagField: OpColumn = OpColumn(0, tagWidth)

  def userWidth: Int = tagField.end

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
  ): Node[UInt] = tagField(user)
