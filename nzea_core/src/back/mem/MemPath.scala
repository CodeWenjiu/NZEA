package nzea_core.back.mem

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Valid, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.{viewOf, ExecSlot, FuView, MemFace, QueueFace}
import nzea_core.isa.{CsrType, Isa}
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One `base:size` window, hex, of the platform's device map (e.g. `0x10000000:0x1000`): an address the platform
  * answers outside memory, which commit traces mark as MMIO.
  */
object MmioWindow:

    def parse(range: String): (BigInt, BigInt) =
        def hex(text: String): BigInt = BigInt(text.trim.stripPrefix("0x").replace("_", ""), 16)
        range.split(":") match
            case Array(base, size) => (hex(base), hex(size))
            case _                 => throw new IllegalArgumentException(s"expected a base:size window, got '$range'")

case class MemPathParameter(isa: Isa, defaultPc: Long, mmioRanges: Seq[String]) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)
    val windows: Seq[(BigInt, BigInt)] = mmioRanges.map(MmioWindow.parse)

given upickle.default.ReadWriter[MemPathParameter] = upickle.default.macroRW

class MemPathLayers(parameter: MemPathParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The memory path's own input: its op window's code, the address base and offset, the store data, and the pc the
  * prediction goes on with.
  */
class LsuInput(config: CoreConfig) extends Bundle:
    val op = Aligned(UInt(MemOp.set(config.isa).width))
    val base = Aligned(UInt(config.width))
    val offset = Aligned(UInt(config.width))
    val storeData = Aligned(UInt(config.width))
    val nextPc = Aligned(UInt(config.width))

object LsuInput:

    /** The memory path's view of the uop's union: the pool's first three slots — the form places the address base, its
      * offset and the store data there — and the fetch's carried next pc.
      */
    given FuView[LsuInput] with

        def view(u: Referable[ExecSlot], config: CoreConfig)(using
            Arena,
            Context,
            Block,
            sourcecode.File,
            sourcecode.Line,
            sourcecode.Name.Machine,
            InstanceContext,
            TypeImpl
        ): Wire[LsuInput] =
            val in = Wire(new LsuInput(config))
            in.op := MemOp.set(config.isa).payload(u.op)
            in.base := u.src.ref(0)
            in.offset := u.src.ref(1)
            in.storeData := u.src.ref(2)
            in.nextPc := u.next_pc
            in

class MemPathIO(parameter: MemPathParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The middle's faces, flipped: the gated uop in and the completion report out, and the queue's request side. */
    val path = Flipped(new MemFace(parameter.config))
    val queue = Flipped(new QueueFace(parameter.config))

    val dbus = Aligned(MemPath.dbusType(parameter.config))

class MemPathProbe(parameter: MemPathParameter) extends DVBundle[MemPathParameter, MemPathLayers](parameter)

/** Memory execution path: one bus request per uop, its answer routed by kind — a load's matched by the request tag, a
  * store's acknowledgment swallowed by count.
  *
  * The path keeps no queue — the held uop is all the state it needs. The request is driven while the uop is there and
  * dropped when it goes away, and every response is consumed, so a response for an abandoned request drains instead of
  * completing the next uop. A store completes on the request's own acceptance — the first cycle the bus takes it — and
  * it is non-speculative then: with the load/store queue on, the request is held back while an older mispredicted
  * branch could still flush it (`flushRisk`), and without one the slot holds the oldest unretired uop and its validity
  * is flush-gated, so a store younger than a resolving mispredict is never presented either way. The same gate covers a
  * load: its request is a bus access, and a speculative one to an unmapped or MMIO window cannot be undone by a flush.
  * The acknowledgment that follows is counted, never matched, so a stale one cannot complete a load. A load completes
  * when its tagged response lands — with the queue on, the slot has already let the uop go and the answer completes the
  * uop's queue entry instead, which is why the request side also leaves `release`, `reqTag`, `offset` and the response
  * behind it. Byte or half accesses are aligned by the bus itself: the request carries the word address, and the path
  * selects inside the word with the offset it holds.
  */
@generator
object MemPath extends Generator[MemPathParameter, MemPathLayers, MemPathIO, MemPathProbe]:
    override def moduleName(parameter: MemPathParameter): String = "MemPath"

    val busIdWidth: Int = 1

    def dbusType(config: CoreConfig) =
        new LiteBusRW(config.width, config.width, DbusUser.userWidth, busIdWidth)

    def architecture(parameter: MemPathParameter) =
        val io = summon[Interface[MemPathIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val uopValid = io.path.issue.valid
        val u = io.path.issue.bits
        val lsuIn = u.viewOf[LsuInput](parameter.config)

        val lsuOps = LsuOp.set(parameter.config.isa)
        val op: Referable[UInt] = lsuIn.op
        val isStore = LsuOp.isStore(op, lsuOps)

        val addr = prefixAdder(lsuIn.base, lsuIn.offset, false.B, width)
        val addrBits: Node[Bits] = Node(addr.asBits)
        // One bus word is `lanes` bytes (four at xlen 32, eight at xlen 64), and the low address bits are the offset inside
        // it: the request carries the word address, the path selects inside the word with the offset it holds.
        val lanes = width / 8
        val offsetWidth = log2Ceil(lanes)
        val offset: Node[UInt] = addrBits.bits(offsetWidth - 1, 0).asUInt

        /** One op's byte strobe: a store lights the lanes it writes, everything else reads. The strobe is selected by
          * the offset bits — a constant mask per data position, one per access size — and a wider access than a lane is
          * aligned by the ISA, so its mask sits at the offset's own alignment.
          */
        def writeStrobe() =
            val byteStrb: Referable[UInt] = muxTree(offset, (0 until lanes).map(i => (BigInt(1) << i).U(lanes)))
            val halfStrb: Referable[UInt] = muxTree(offset, (0 until lanes).map(i => (BigInt(3) << (i & ~1)).U(lanes)))
            val wordStrb: Referable[UInt] =
                muxTree(offset, (0 until lanes).map(i => (BigInt(0xf) << (i & ~3)).U(lanes)))
            val doubleStrb: Referable[UInt] = ((BigInt(1) << lanes) - 1).U(lanes)
            val zeroStrb: Referable[UInt] = BigInt(0).U(lanes)
            def strobe(v: LsuOp): Referable[UInt] = v match
                case LsuOp.SB                                                                      => byteStrb
                case LsuOp.SH                                                                      => halfStrb
                case LsuOp.SW                                                                      => wordStrb
                case LsuOp.SD                                                                      => doubleStrb
                case LsuOp.LB | LsuOp.LH | LsuOp.LW | LsuOp.LBU | LsuOp.LHU | LsuOp.LWU | LsuOp.LD => zeroStrb
            mux1H(op, lsuOps.variants.map(strobe), lanes)

        /** The store data, shifted into the addressed lane by the same offset bits. */
        def storeWord() =
            val storeData: Node[Bits] = Node(lsuIn.storeData.asBits)
            muxTree(offset, (0 until lanes).map(i => (storeData << (i * 8)).bits(width - 1, 0)))

        /** One request per uop, presented in the uop's first cycle — address, data and strobe are combinational from
          * the held uop and already valid then — and held until the bus accepts it. `fire` is that acceptance: a store
          * needs nothing beyond it, while a load's completion waits for its tagged response. The tag advances on that
          * same first cycle, so the request carries its own tag and `curTag` keeps it for the response match.
          *
          * The request waits for the queue's room and for the queue's `flushRisk` to clear; both are constants when no
          * queue is built, so the wait folds away. `flushRisk` gates every access, stores and loads alike: a store's
          * write that leaves while an older branch could still flush cannot be taken back, and a load's request is a
          * bus access that cannot be taken back either — a speculative one to an unmapped window faults the bus and one
          * to an MMIO window has side effects, neither of which a flush can undo. While the queue holds a mispredicted
          * branch the younger access simply waits, and the flush that is coming drops the uop before it issues. `held`
          * latches the offer itself, not the gated request: an offer that arrives while the gates are closed is not
          * lost, the next cycle presents it again.
          */
        def holdRequest(wdata: Referable[UInt], wstrb: Referable[UInt]) =
            val uopFirst = io.path.issue.first
            val tag = RegInit(0.U(DbusUser.tagWidth))
            val curTag = RegInit(0.U(DbusUser.tagWidth))
            val tagNext = (tag.asBits.asUInt + 1.U(DbusUser.tagWidth)).asBits.bits(DbusUser.tagWidth - 1, 0).asUInt
            when(uopFirst) {
                curTag := tag
                tag := tagNext
            }

            val canIssue = io.queue.canAccept & !io.queue.flushRisk
            val held = RegInit(false.B)
            val wants = uopValid & (uopFirst | held)
            val asking = wants & canIssue
            val fire: Node[Bool] = asking & io.dbus.req.ready
            when(wants & !fire) { held := true.B }
            when(!uopValid | fire) { held := false.B }
            val reqTag: Node[UInt] = uopFirst ? (tag, curTag)

            io.dbus.req.valid := asking
            io.dbus.req.bits.addr := (addrBits.bits(width - 1, offsetWidth) ## 0.B(offsetWidth)).asUInt
            io.dbus.req.bits.wdata := wdata
            io.dbus.req.bits.wen := isStore
            io.dbus.req.bits.wstrb := wstrb
            io.dbus.req.bits.user := DbusUser.pack(reqTag)
            io.dbus.req.bits.id := 0.U(busIdWidth)
            (reqTag, fire)

        val wstrb = writeStrobe()
        val wdata = storeWord()
        val (reqTag, reqFire) = holdRequest(wdata.asUInt, wstrb)
        io.dbus.resp.ready := true.B

        // The request side and the answers, handed to the queue: the acceptance is a release's trigger, the tag and
        // offset are what a new entry records, and the response is what a load's entry is completed from.
        io.path.slotRelease := reqFire
        io.queue.reqTag := reqTag
        io.queue.offset := offset
        io.queue.resp.valid := io.dbus.resp.valid
        io.queue.resp.bits.tag := DbusUser.tagOf(io.dbus.resp.bits.user)
        io.queue.resp.bits.data := io.dbus.resp.bits.data

        // A store's answer is only the bus's acknowledgment, and the store is non-speculative when its write is accepted:
        // the slot holds it while its older instructions retire, and the slot's own validity is flush-gated, so a store
        // younger than a resolving mispredict is never presented. The write's acceptance is therefore the store's
        // completion, and the acknowledgment that follows is a response nobody waits for — it drains, its tag being older
        // than the current uop's. That is what the tag width buys (see [[DbusUser]]): with a store's acknowledgment still
        // in flight the fabric may answer a younger access first (it keeps order per slave, not per master), so "which
        // response is mine" cannot be answered by position.
        val hit = io.dbus.resp.valid & uopValid & (DbusUser.tagOf(io.dbus.resp.bits.user) === reqTag)

        /** The load result: sign- or zero-extended from the addressed byte, half, word or the whole bus word, selected
          * by the offset the uop holds. The stores have nothing to answer with. The same selection reads the bus for a
          * queue entry's answer ([[LsuData]]), where the offset comes from the entry instead.
          */
        val loadData = LsuData.loadWord(width, io.dbus.resp.bits.data, offset, op, lsuOps)

        /** Whether the address falls in one of the platform's device windows — the commit trace's MMIO mark. */
        def mmioAddress(): Referable[Bool] =
            parameter.windows.foldLeft(false.B: Referable[Bool]): (acc, window) =>
                val (base, size) = window
                acc | ((addr >= base.U(width)) & (addr < (base + size).U(width)))

        val isMmio = mmioAddress()

        /** The one-shot result of the uop: a store's is its write's acceptance — the acknowledgment that follows is
          * nobody's — and a load's is its own tagged response, whose data it carries. The redirect is the branch's
          * alone.
          */
        def result(): Unit =
            io.path.done := (reqFire & isStore) | hit
            io.path.result.data := (isStore ? (0.U(width), loadData.asUInt))
            io.path.result.next_pc := lsuIn.nextPc
            io.path.result.csr_type := CsrType.None.lit.U(CsrType.width)
            io.path.result.csr_data := 0.U(width)
            io.path.result.is_mmio := isMmio
            io.path.result.mispredict := false.B

        result()
