package nzea_core.front.ifu

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The instruction bus's `user` field, `{pc, next_pc, fall_through, epoch}`, carried on the request and echoed on the
  * response.
  *
  * The pc rides along so the fetcher reads back which fetch a response belongs to instead of keeping a table of
  * outstanding requests, and `next_pc` — the pc the fetch stream goes on with after the fetched instruction, the
  * fetcher's prediction — rides with it for the same reason: the response hands the prediction back beside the
  * instruction, so the pipeline never reconstructs it. `fall_through` — the instruction's own `pc + 4`, made at the
  * fetch — rides beside it so no stage below rebuilds that adder. The epoch is the generation of the fetch stream: it
  * increments on every redirect, so a response from a killed stream is recognized and digested — the bus itself carries
  * no cancellation.
  *
  * `epochBits` must outlast the worst-case response latency measured in redirects: an epoch that wraps while a stale
  * response is still in flight would let that response pass as current.
  */
object IbusUser:

  val epochBits: Int = 4

  /** The user field for one address width: the epoch low-anchored, then the stream's next pc, the instruction's
    * fall-through and the fetch's pc.
    */
  private final class Layout(addrWidth: Int) extends Encoding.Aggregate:

    val fields: Seq[(String, Int)] =
      Seq("epoch" -> epochBits, "next_pc" -> addrWidth, "fall_through" -> addrWidth, "pc" -> addrWidth)

  /** Host-side, so one instance per call costs nothing; the layout is the declaration the accessors below read. */
  def layout(addrWidth: Int): Encoding.Aggregate = new Layout(addrWidth)

  def userWidth(addrWidth: Int): Int = layout(addrWidth).width

  def pack[PC <: Referable[UInt], N <: Referable[UInt], F <: Referable[UInt], E <: Referable[UInt]](
      pc: PC,
      nextPc: N,
      fallThrough: F,
      epoch: E
  )(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] =
    val pcBits: Node[Bits] = Node(pc.asBits)
    val nextBits: Node[Bits] = Node(nextPc.asBits)
    val fallBits: Node[Bits] = Node(fallThrough.asBits)
    val epochBits: Node[Bits] = Node(epoch.asBits)
    // Packed highest-first, the mirror of the layout's low-anchored field order (`pc`, `fall_through`, `next_pc`,
    // `epoch`): the two sides of this field are one declaration and must be changed together.
    val packed: Node[Bits] = Node(pcBits ## fallBits ## nextBits ## epochBits)
    packed.asUInt

  def epochOf[U <: Referable[UInt]](user: U, addrWidth: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] = layout(addrWidth).read("epoch", user)

  def pcOf[U <: Referable[UInt]](user: U, addrWidth: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] = layout(addrWidth).read("pc", user)

  def nextPcOf[U <: Referable[UInt]](user: U, addrWidth: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] = layout(addrWidth).read("next_pc", user)

  def fallThroughOf[U <: Referable[UInt]](user: U, addrWidth: Int)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] = layout(addrWidth).read("fall_through", user)
