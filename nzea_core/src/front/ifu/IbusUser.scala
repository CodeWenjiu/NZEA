package nzea_core.front.ifu

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The instruction bus's `user` field, `{pc, epoch}`, carried on the request and echoed on the response.
  *
  * The pc rides along so the fetcher reads back which fetch a response belongs to instead of keeping a table of
  * outstanding requests. The epoch is the generation of the fetch stream: it increments on every redirect, so a
  * response from a killed stream is recognized and digested — the bus itself carries no cancellation.
  *
  * `epochBits` must outlast the worst-case response latency measured in redirects: an epoch that wraps while a stale
  * response is still in flight would let that response pass as current.
  */
object IbusUser:

  val epochBits: Int = 4

  /** Epoch column, low-anchored, so the pc's position follows the address width alone. */
  val epochField: Encoding.Column = Encoding.Column(0, epochBits)

  /** Pc column: the address the request was issued for. */
  def pcField(addrWidth: Int): Encoding.Column = Encoding.Column(epochField.end, addrWidth)

  def userWidth(addrWidth: Int): Int = pcField(addrWidth).end

  def pack[PC <: Referable[UInt], E <: Referable[UInt]](pc: PC, epoch: E)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] =
    val pcBits: Node[Bits] = Node(pc.asBits)
    val epochBits: Node[Bits] = Node(epoch.asBits)
    val packed: Node[Bits] = Node(pcBits ## epochBits)
    packed.asUInt

  def epochOf[U <: Referable[UInt]](user: U)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] = epochField(user)

  def pcOf[U <: Referable[UInt]](user: U, addrWidth: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] = pcField(addrWidth)(user)
