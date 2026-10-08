package nzea_core.front.ifu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.Decoupled
import nzea_core.config.{BpuConfig, CoreConfig}
import nzea_core.back.BpUpdate
import nzea_core.front.ifu.bp.{Bpu, BpuParameter}
import nzea_core.isa.Isa
import nzea_rtl.bus.LiteBusRO
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

case class IfuParameter(isa: Isa, defaultPc: Long, bpu: Option[BpuConfig]) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IfuParameter] = upickle.default.macroRW

class IfuLayers(parameter: IfuParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IfuIO(parameter: IfuParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val ibus = Aligned(Ifu.busType(parameter.config))

    /** The flush event, seen from the fetcher's side: `next_pc` is where it restarts, and the event starts a new epoch.
      */
    val redirect = Flipped(new FlushBus(parameter.config.width))

    /** The resolved branches the predictor trains on; unused (and folded away) with no predictor. */
    /** The predictor's training, when a predictor is built: the port follows the config, as it does on the back's end.
      */
    val bp_update: Option[BundleField[ValidIO[BpUpdate]]] =
        Option.when(parameter.bpu.isDefined)(Flipped(Valid(new BpUpdate(parameter.config.width))))

    val out = Aligned(Decoupled(new FetchedInst(parameter.config.width)))

class IfuProbe(parameter: IfuParameter) extends DVBundle[IfuParameter, IfuLayers](parameter)

/** In-order fetcher: `pc`, one request at a time, and the prediction that steers it.
  *
  * The flush event is the only event in this module's world: its `next_pc` steers pc and it starts a new epoch, which
  * is also how the fetch stream is cancelled — a response of a killed stream is consumed and digested, never refused.
  * The fetcher never gates on reset: it asks and lets the slave refuse (a slave that is not ready does not accept), so
  * the pc only moves on a real transfer.
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
        // The pc register's own fall-through, registered beside it: loaded from the same select that loads the pc, so the
        // two always describe the same fetch ([[fallThrough]] below explains why it is made there).
        val fallThrough = RegInit(((pcReset + 4) & ((BigInt(1) << width) - 1)).U(width))
        val epoch = RegInit(0.U(IbusUser.epochBits))

        // The prediction the request carries for the instruction at `pc`, and the pc register's next value, in one
        // structure at every level: the predictor is addressed with the pc's *next* value — the prediction for the next
        // fetch is computed while this one is on the bus — and answers it from its own registers a cycle later, which is
        // exactly when that value is the pc. With no predictor the answer is the fall-through alone, so the config
        // only decides what the answer is worth.
        val bpu = Bpu.instantiate(BpuParameter(parameter.config.isa, parameter.config.defaultPc, parameter.bpu))

        val nextPc: Referable[UInt] = muxCase(
            pc,
            Seq[(Referable[Bool], Referable[UInt])](
                io.redirect.valid -> io.redirect.next_pc,
                io.ibus.req.fire -> bpu.io.pred_next_pc
            )
        )
        pc := nextPc

        bpu.io.clock := io.clock
        bpu.io.resetN := io.resetN
        bpu.io.update.zip(io.bp_update).foreach((update, port) => update :<= port)
        // The read address is the pc register's next value: next cycle's register, one cycle early.
        bpu.io.lookup_pc := nextPc
        // The instruction's own fall-through, registered beside the pc: both are loaded from `nextPc`, so the register
        // always holds `pc + 4` and the two never disagree. It is made *after* the select that computes the pc's next
        // value, not off the pc register, because that select is the fetch loop's whole body: the loop is
        // pc -> prediction -> pc, and with the +4 on the loop's own side it measured a 30-level carry chain (abc
        // decomposes the adder into a ripple) in the loop, in the request's user packing and in the BTB's read address
        // at once. Here the adder feeds only this register — one 32-bit op on a path of its own — and every one of those
        // three cones reads a register instead.
        fallThrough := constAdder(nextPc, 4, width)
        bpu.io.fall_through := fallThrough

        when(io.redirect.valid) {
            epoch := (epoch.asBits.asUInt + 1.U(IbusUser.epochBits)).asBits.bits(IbusUser.epochBits - 1, 0).asUInt
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
        io.ibus.req.bits.user := IbusUser.pack(pc, bpu.io.pred_next_pc, fallThrough, epoch, bpu.io.pred_count)
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
