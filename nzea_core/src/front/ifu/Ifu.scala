package nzea_core.front.ifu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.Decoupled
import nzea_core.{BpuLevel, CoreConfig}
import nzea_core.back.BpUpdate
import nzea_core.front.ifu.bp.{Bpu, BpuParameter}
import nzea_core.isa.Isa
import nzea_rtl.LiteBusRO
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One fetch result: the pc the word was read from, the word itself, the pc the fetch stream goes on with after it —
  * the prediction the fetcher made for it (the fall-through at `Off`, the tables' answer on a predicted level) — the
  * instruction's own fall-through, and the direction counter the prediction read, both carried on the response rather
  * than reconstructed later.
  */
class FetchedInst(width: Int) extends Bundle:
  val pc = Aligned(UInt(width))
  val inst = Aligned(UInt(32))
  val next_pc = Aligned(UInt(width))
  val fall_through = Aligned(UInt(width))
  val pht_count = Aligned(UInt(BpUpdate.phtCountBits))

case class IfuParameter(isa: Isa, defaultPc: Long, bpu: BpuLevel) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IfuParameter] = upickle.default.macroRW

class IfuLayers(parameter: IfuParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IfuIO(parameter: IfuParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val ibus = Aligned(Ifu.busType(parameter.config))
  val redirect = Flipped(Valid(UInt(parameter.config.width)))

  /** The resolved branches the predictor trains on; unused (and folded away) at `Off`. */
  val bp_update = Flipped(Valid(new BpUpdate(parameter.config.width)))
  val out = Aligned(Decoupled(new FetchedInst(parameter.config.width)))

class IfuProbe(parameter: IfuParameter) extends DVBundle[IfuParameter, IfuLayers](parameter)

/** In-order fetcher: `pc`, one request at a time, and the prediction that steers it.
  *
  * Redirect is the only event in this module's world: it steers pc and starts a new epoch, which is also how the fetch
  * stream is cancelled — a response of a killed stream is consumed and digested, never refused. The fetcher never gates
  * on reset: it asks and lets the slave refuse (a slave that is not ready does not accept), so the pc only moves on a
  * real transfer.
  *
  * The prediction is the predictor's ([[Bpu]]), and this module only carries it: every cycle the predictor is addressed
  * with the pc register's *next* value — the same signal that updates the register — and answers a cycle later, out of
  * its own registers, for the pc the register will hold then. That answer is both the register's next value and the
  * prediction the request carries, and the training the fetch gathered for the instruction — the direction counter the
  * prediction read — rides the request's user field beside them, so every stage below reads all of it off the
  * instruction instead of recomputing it.
  */
@generator
object Ifu extends Generator[IfuParameter, IfuLayers, IfuIO, IfuProbe]:
  override def moduleName(parameter: IfuParameter): String = "Ifu"

  /** Fetch bus shape: address and data are the ISA word width. Every request is id 0 — a fetch stream is one class of
    * traffic, and cancellation travels in the user field rather than in the id.
    */
  val busIdWidth: Int = 1

  def busType(config: CoreConfig) =
    new LiteBusRO(config.width, config.width, IbusUser.userWidth(config.width), busIdWidth)

  def architecture(parameter: IfuParameter) =
    val io = summon[Interface[IfuIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    // The platform's reset pc, masked to the register width: a BigInt shift, because `1L << 64` wraps around in a Long.
    val pcReset = BigInt(parameter.config.defaultPc) & ((BigInt(1) << width) - 1)
    val pc = RegInit(pcReset.U(width))
    val epoch = RegInit(0.U(IbusUser.epochBits))

    // The prediction the request carries for the instruction at `pc`, and the pc register's next value, in one
    // structure at every level: the predictor is addressed with the pc's *next* value — the prediction for the next
    // fetch is computed while this one is on the bus — and answers it from its own registers a cycle later, which is
    // exactly when that value is the pc. At `Off` the answer is the fall-through alone, so the level only decides what
    // the answer is worth.
    val bpu = Bpu.instantiate(BpuParameter(parameter.config.isa, parameter.config.defaultPc, parameter.bpu))

    val epochNext = (epoch.asBits.asUInt + 1.U(IbusUser.epochBits)).asBits.bits(IbusUser.epochBits - 1, 0).asUInt

    val nextPc: Referable[UInt] = muxCase(
      pc,
      Seq[(Referable[Bool], Referable[UInt])](
        io.redirect.valid -> io.redirect.bits,
        io.ibus.req.fire -> bpu.io.pred_next_pc
      )
    )
    pc := nextPc

    bpu.io.clock := io.clock
    bpu.io.resetN := io.resetN
    bpu.io.update :<= io.bp_update
    // The read address is the pc register's next value: next cycle's register, one cycle early.
    bpu.io.lookup_pc := nextPc
    // The instruction's own fall-through, made once on this side: the request packs it (below) and the predictor's
    // composition defaults to it, so the two can never disagree and the +4 exists once. Live off the pc register, it
    // sits beside the pc, not after the select that computes the pc's next value.
    val pcFallThrough: Node[UInt] = pcPlus4(pc, width)
    bpu.io.fall_through := pcFallThrough

    when(io.redirect.valid) {
      epoch := epochNext
    }

    // Fetch follows the memory path's contract: the request carries the word address and the fetch picks the 32-bit
    // group the pc points at out of the answered word, so no slave is ever asked for a misaligned access. Instructions
    // are 4-byte aligned, so only group starts are reachable — `i & ~3` keeps every entry of the select a valid slice
    // (at xlen 32 there is exactly one group and the select folds away).
    val lanes = width / 8
    val offsetWidth = log2Ceil(lanes)
    val pcBits: Node[Bits] = Node(pc.asBits)
    val wordAddr: Node[UInt] = (pcBits.bits(width - 1, offsetWidth) ## 0.B(offsetWidth)).asUInt
    // The group comes from the response's own pc: a response arrives a cycle after its request, when the register has
    // already moved on.
    val respPc: Node[UInt] = IbusUser.pcOf(io.ibus.resp.bits.user, width)
    val respNextPc: Node[UInt] = IbusUser.nextPcOf(io.ibus.resp.bits.user, width)
    val respPcBits: Node[Bits] = Node(respPc.asBits)
    val offset: Node[UInt] = respPcBits.bits(offsetWidth - 1, 0).asUInt
    val rdata: Node[Bits] = Node(io.ibus.resp.bits.data.asBits)
    val inst: Node[UInt] =
      muxTree(offset, (0 until lanes).map(i => rdata.bits((i & ~3) * 8 + 31, (i & ~3) * 8))).asUInt

    io.ibus.req.valid := io.out.ready
    io.ibus.req.bits.addr := wordAddr
    // The request carries the fetch's whole context: which pc it is, the pc the stream goes on with (the prediction),
    // the instruction's own fall-through — made here, on the port's own path, so no stage below rebuilds it — the
    // direction counter the prediction read (training evidence), and the epoch that says whether it is still wanted.
    io.ibus.req.bits.user := IbusUser.pack(pc, bpu.io.pred_next_pc, pcFallThrough, epoch, bpu.io.pred_count)
    io.ibus.req.bits.id := 0.U(Ifu.busIdWidth)

    // `out.ready` is the only condition on accepting a response: a fetch from a killed stream is consumed and digested
    // (out.valid stays low) instead of being refused, so no request is left dangling on the bus.
    io.ibus.resp.ready := io.out.ready
    io.out.valid := io.ibus.resp.valid & (IbusUser.epochOf(io.ibus.resp.bits.user, width) === epoch)
    io.out.bits.pc := respPc
    io.out.bits.inst := inst
    io.out.bits.next_pc := respNextPc
    io.out.bits.fall_through := IbusUser.fallThroughOf(io.ibus.resp.bits.user, width)
    io.out.bits.pht_count := IbusUser.phtCountOf(io.ibus.resp.bits.user, width)
