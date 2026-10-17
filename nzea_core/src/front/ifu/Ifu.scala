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

/** One fetch result: the pc the word was read from, the word itself, the pc the fetch stream goes on with — the
  * prediction, not the fall-through — the instruction's own fall-through, and the direction counter the prediction
  * read.
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

    /** The flush event, seen from the fetcher's side: `next_pc` is where it restarts, and the event starts a new
      * epoch.
      */
    val redirect = Flipped(new FlushBus(parameter.config.width))

    /** The predictor's training, when a predictor is built: the port follows the config, as it does on the back's end.
      */
    val bp_update: Option[BundleField[ValidIO[BpUpdate]]] =
        Option.when(parameter.bpu.isDefined)(Flipped(Valid(new BpUpdate(parameter.config.width))))

    val out = Aligned(Decoupled(new FetchedInst(parameter.config.width)))

class IfuProbe(parameter: IfuParameter) extends DVBundle[IfuParameter, IfuLayers](parameter)

/** In-order fetcher: `pc`, one request at a time, and the prediction that steers it.
  *
  * Both the redirect's `next_pc` and the prediction steer the pc, and a redirect starts a new epoch; a response of a
  * killed stream is consumed and digested (the epoch check lowers `out.valid`), never refused, so no request is left
  * dangling on the bus. The fetcher never gates on reset: it asks and lets the slave refuse, so the pc only moves on a
  * real transfer.
  *
  * The predictor is addressed with the pc register's *next* value — the same signal that updates the register — and
  * answers a cycle later, out of its own registers, for the pc the register will hold then: that answer is both the
  * register's next value and the prediction the request carries.
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
        // two always describe the same fetch.
        val fallThrough = RegInit(((pcReset + 4) & ((BigInt(1) << width) - 1)).U(width))
        val epoch = RegInit(0.U(IbusUser.epochBits))

        // With no predictor the prediction is the fall-through alone, so the config only decides what the answer is
        // worth.
        val bpu = Bpu.instantiate(BpuParameter(parameter.config.isa, parameter.config.defaultPc, parameter.bpu))

        // Kept whole, like the predictor's own select: this is the fetch loop's body — the prediction becomes the pc's
        // next value — and a mapper left to itself folds the wide mux into the cones on either side of it.
        val nextPc: Referable[UInt] = dontTouch(
            muxCase(
                pc,
                Seq[(Referable[Bool], Referable[UInt])](
                    io.redirect.valid -> io.redirect.next_pc,
                    io.ibus.req.fire -> bpu.io.pred_next_pc
                )
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
        // value, not off the pc register: with the +4 on the loop's own side it measured a 30-level carry chain (abc
        // decomposes the adder into a ripple) in the loop, in the request's user packing and in the BTB's read address
        // at once; here the adder feeds only this register and those three cones read a register instead.
        fallThrough := constAdder(nextPc, 4, width)
        bpu.io.fall_through := fallThrough

        when(io.redirect.valid) {
            epoch := (epoch.asBits.asUInt + 1.U(IbusUser.epochBits)).asBits.bits(IbusUser.epochBits - 1, 0).asUInt
        }

        // Fetch follows the memory path's contract: the request carries the word address and the fetch picks the
        // 32-bit group the pc points at out of the answered word, so no slave is ever asked for a misaligned access.
        // Instructions are 4-byte aligned, so only group starts are reachable — `i & ~3` keeps every entry of the
        // select a valid slice.
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

        // The fetcher always has a request to make: the bus's own ready is the only gate on the request, so the path it
        // drives — the pc, the prediction, the user packing that rides with them — never follows the pipeline's ready
        // chain. What bounds how far the fetch runs ahead is the buffer at the core's boundary, not this module. The
        // response side is where the pipeline's room belongs: a response waits for `out.ready`, so nothing is delivered
        // to a pipeline that cannot take it.
        io.ibus.req.valid := true.B
        io.ibus.req.bits.addr := wordAddr
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
