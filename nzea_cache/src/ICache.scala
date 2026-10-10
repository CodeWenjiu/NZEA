package nzea_cache

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.RtlFlow
import nzea_rtl.bus.*
import nzea_rtl.bus.given
import nzea_rtl.stat.{StatRegion, StatScope}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The counters the caches declare. */
object CacheRegions:
    val Cache = StatRegion("cache", "Cache")

case class ICacheParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    config: CacheConfig,
    cacheable: Seq[AddrRange],
    outstanding: Int,
    flow: RtlFlow,
    stats: StatScope
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(config.tagBits(addrWidth) >= 1, s"the address has no room for a tag: ${config.tagBits(addrWidth)} bits")
    require(config.lineBeats(dataWidth) >= 1, s"a line of ${config.lineBytes} bytes has no beats of $dataWidth bits")
    require(cacheable.nonEmpty, "a cache needs at least one cacheable window")

    require(
        config.storage == CacheStorageKind.Registers,
        s"the split read is a flop array's — a narrow tag port beside the row port; a macro reads whole rows on one " +
            s"port, got ${config.storage.name}"
    )

given upickle.default.ReadWriter[ICacheParameter] = upickle.default.macroRW

class ICacheLayers(parameter: ICacheParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ICacheIO(parameter: ICacheParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The core side: the cache is its slave. */
    val top = Flipped(new LiteBusRO(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

    /** The fabric side: the cache is its master. The instruction path only ever reads, but the fabric takes the uniform
      * read-write port, and the write fields stay at zero.
      */
    val bottom =
        Aligned(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class ICacheProbe(parameter: ICacheParameter) extends DVBundle[ICacheParameter, ICacheLayers](parameter)

/** The instruction cache: a set-associative line store in front of the fetch's memory side.
  *
  * The hit path is a two-stage pipeline that accepts one request per cycle: the head cycle reads the head's set — the
  * four ways' whole rows — into the result stage's registers, and the result stage compares the captured tags, settles
  * the way and picks the word from those same registers. The read is what the stage holds, so the array's read mux — a
  * 64-set mux per bit per way — ends at a register instead of reaching the answer, and neither the read nor the
  * compare's result ever goes back into an address.
  *
  * A miss freezes the queue — the in-order fetch cannot be answered around it — and hands the line's address to
  * [[CacheRefill]]; when the engine answers, the line is installed, the replacement state touched, and the missed word
  * returned as the response. A request outside the cacheable windows skips the store and goes to the engine as a
  * one-beat read, so MMIO is never cached and never mistaken for a hit.
  */
@generator
object ICache extends Generator[ICacheParameter, ICacheLayers, ICacheIO, ICacheProbe]:
    override def moduleName(parameter: ICacheParameter): String = "ICache"

    def architecture(parameter: ICacheParameter) =
        val io = summon[Interface[ICacheIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val aw = parameter.addrWidth
        val idxBits = c.indexBits
        val offBits = c.offsetBits
        val wayBits = c.wayBits
        val tagBits = c.tagBits(aw)
        val lineBeats = c.lineBeats(parameter.dataWidth)
        val wordOffBits = c.wordOffBits(parameter.dataWidth)
        val wordBits = parameter.dataWidth
        val byteOffBits = log2Ceil(wordBits / 8)
        val rowWidth = c.rowWidth(aw)
        val padBits = rowWidth - tagBits - c.lineBits

        val storage =
            CacheStorage.instantiate(CacheStorageParameter(c.sets, c.ways, rowWidth, tagBits, c.lineBits, c.storage))
        storage.io.clock := io.clock
        storage.io.resetN := io.resetN
        val refill = CacheRefill.instantiate(
            CacheRefillParameter(
                aw,
                parameter.dataWidth,
                parameter.userWidth,
                parameter.idWidth,
                lineBeats,
                wordOffBits,
                parameter.outstanding
            )
        )
        refill.io.clock := io.clock
        refill.io.resetN := io.resetN
        val repl = new ReplacementState(c.sets, c.ways, c.replacement)

        /** The cache's views of an address and of a storage row: fields read by name, not by bit position. */
        def addrOf(a: Referable[UInt]): Node[CacheAddr] =
            Node(a.asBits).asType(new CacheAddr(aw, idxBits, offBits, byteOffBits))
        def lineOf(row: Referable[UInt]): Node[CacheLine] =
            val bits = Node(row.asBits)
            (if padBits > 0 then bits.bits(rowWidth - 1, padBits) else bits)
                .asType(new CacheLine(tagBits, lineBeats, wordBits))
        def lineBase(a: Node[CacheAddr]): Node[UInt] =
            (a.tag.asBits ## a.set.asBits ## 0.B(offBits)).asUInt
        def wordAligned(a: Node[CacheAddr]): Node[UInt] =
            (a.tag.asBits ## a.set.asBits ## a.word.asBits ## 0.B(byteOffBits)).asUInt
        def cacheableOf(a: Referable[UInt]): Node[Bool] =
            parameter.cacheable.map(r => (a >= r.base.U(aw)) & (a < r.endExclusive.U(aw))).reduce(_ | _)

        // ── The request queue: two entries; the head's row is live on the storage's read port ──
        val headValid = RegInit(false.B)
        val headAddr = Reg(UInt(aw))
        val headUser = Reg(UInt(parameter.userWidth))
        val headId = Reg(UInt(parameter.idWidth))
        val tailValid = RegInit(false.B)
        val tailAddr = Reg(UInt(aw))
        val tailUser = Reg(UInt(parameter.userWidth))
        val tailId = Reg(UInt(parameter.idWidth))

        // ── The result stage: the head's read captured, held until the fetch takes the answer ──
        // The stage holds the *read* — the four ways' whole rows of the head's set, and the set's valid bits — so the
        // compare, the way and the word all start from registers: the array's read mux ends here instead of reaching
        // the answer. One request per cycle, the same as before; after that the stage is held until the fetch takes
        // what it formed.
        val t1Valid = RegInit(false.B)
        val t1Rows = Reg(Vec(c.ways, UInt(rowWidth)))
        val t1Vd = Reg(Vec(c.ways, Bool()))
        val t1Addr = Reg(UInt(aw))
        val t1User = Reg(UInt(parameter.userWidth))
        val t1Id = Reg(UInt(parameter.idWidth))
        // The stage's one-shot: the first cycle a request is in it, its compare settles the miss slot or the
        // replacement touch. The stage may be held for cycles after, and neither may fire twice.
        val t1Decided = RegInit(false.B)

        // ── The miss the engine is working on: what the install needs once it answers ──
        // The slot is written when the miss is promoted and stands until the engine takes it, so a miss promoted in
        // the same cycle the engine finishes the previous one is not lost.
        val missPending = RegInit(false.B)
        val missAddr = Reg(UInt(aw))
        val missSet = Reg(UInt(idxBits))
        val missTag = Reg(UInt(tagBits))
        val missWay = Reg(UInt(wayBits))
        val missWordOff = Reg(UInt(wordOffBits))
        val missCacheable = RegInit(false.B)

        // ── The hit's replacement touch: registered, so the tree update starts a cycle after the compare ──
        val touchPending = RegInit(false.B)
        val touchSet = Reg(UInt(idxBits))
        val touchWay = Reg(UInt(wayBits))

        // The stage's view of the captured read: the tag is the row's own slice, the set's valids came along, and the
        // answer's word is the line's, selected by the address's word bits. All of it starts from registers.
        val t1 = addrOf(t1Addr)
        val t1Tag = t1.tag
        val t1Cacheable = cacheableOf(t1Addr)
        val t1Hits = (0 until c.ways).map(w => t1Vd.ref(w) & (lineOf(t1Rows(w)).tag === t1Tag))
        val t1Hit = t1Hits.reduce(_ | _) & t1Cacheable
        val t1Miss = !t1Hit
        val t1Way = muxCase(0.U(wayBits), t1Hits.zipWithIndex.map((hit, w) => (hit, w.U(wayBits))))
        val t1Word = muxTree(t1Way, (0 until c.ways).map(w => lineOf(t1Rows(w)).data.ref(t1.word)))

        // The victim: a free way first, the replacement state only once the set is full.
        val freeWay = muxCase(0.U(wayBits), (0 until c.ways).map(w => (!t1Vd.ref(w), w.U(wayBits))))
        val hasFree = (0 until c.ways).map(w => !t1Vd.ref(w)).reduce(_ | _)
        val victim = hasFree ? (freeWay, repl.way(t1.set))

        val missReady = refill.io.done
        // The answer to the fetch, through a skid: the fetch's readiness is the core's whole back-pressure cone, and the
        // queue's own handover below — `t1Free`, hence `promote` and `accept` — reads it. Buffered, the handover depends
        // only on the buffer's own state, so the consumer's readiness stays outside the cache; the bypass keeps the
        // common case at the same latency. The refill's ack follows the handover, not the consumer's take: once the
        // answer is in the buffer, the engine is free to start the next line.
        val topResp = Wire(io.top.resp.getType)
        responseConnect(topResp, io.top.resp, "topResp")
        val respValid = t1Valid & (!t1Miss | missReady)
        topResp.valid := respValid
        topResp.bits.data := t1Miss ? (refill.io.bypassData, t1Word)
        topResp.bits.user := t1User
        topResp.bits.id := t1Id
        val respFire = respValid & topResp.ready

        // The result stage will be free next cycle: the head's row may be handed over.
        val t1Free = !t1Valid | respFire
        val promote = headValid & t1Free

        // The head, live: its set is what the read muxes, and the result stage captures the read at the handover.
        val head = addrOf(headAddr)
        val headSet = head.set

        val accept = !headValid | !tailValid | promote
        io.top.req.ready := accept
        val acceptFire = io.top.req.valid & accept

        // ── The storage's read: the head's set, live — the whole row per way, what the result stage captures ──
        storage.io.rdSet := headSet
        storage.io.rdEn := headValid
        storage.io.vdSet := headSet

        // ── The engine ──
        // A request outside the windows never hits, so one condition marks the miss for both the refill and the
        // uncached read; the flag only decides whether the line is installed. The engine takes the mark when it is
        // idle, so a miss promoted as the previous one finishes waits a cycle instead of being dropped.
        refill.io.start := missPending & !refill.io.busy
        val missView = addrOf(missAddr)
        refill.io.startAddr := missCacheable ? (lineBase(missView), wordAligned(missView))
        refill.io.startWordOff := missCacheable ? (missWordOff, 0.U(wordOffBits))
        refill.io.startUncached := !missCacheable
        refill.io.ack := t1Miss & respFire
        when(refill.io.start) { missPending := false.B }

        // The row to install: the missed tag above the engine's line, the macro's byte padding above both.
        val installed = Wire(new CacheLine(tagBits, lineBeats, wordBits))
        installed.tag := missTag
        installed.data :<= refill.io.line.asBits.asVec(UInt(wordBits))
        val row = if padBits > 0 then installed.asBits.pad(rowWidth).asUInt else installed.asBits.asUInt
        storage.io.wrEn := refill.io.install
        storage.io.wrSet := missSet
        storage.io.wrWay := missWay
        storage.io.wrData := row
        storage.io.vdWrEn := refill.io.install
        storage.io.vdWrSet := missSet
        storage.io.vdWrWay := missWay
        storage.io.vdWrVal := true.B
        // The instruction cache has no stores, so nothing ever dirties a line.
        storage.io.dtSet := headSet
        storage.io.dtWrEn := false.B
        storage.io.dtWrSet := 0.U(idxBits)
        storage.io.dtWrWay := 0.U(wayBits)
        storage.io.dtWrVal := false.B
        when(refill.io.install) { repl.touch(missSet, missWay) }

        // ── Pipeline advance ──
        // The head's result goes to the result stage, and a successor takes its place: the tail shifting up, or a
        // request accepted the same cycle. A request entering a busy head waits in the tail instead.
        val headFromTail = promote & tailValid
        val headFromNew = acceptFire & (!headValid | (promote & !tailValid))
        when(headFromTail) {
            headValid := true.B
            headAddr := tailAddr
            headUser := tailUser
            headId := tailId
        }.otherwise {
            when(headFromNew) {
                headValid := true.B
                headAddr := io.top.req.bits.addr
                headUser := io.top.req.bits.user
                headId := io.top.req.bits.id
            }.otherwise {
                when(promote) { headValid := false.B }
            }
        }
        // The tail takes a request only when the head does not: with the head busy and held, or with it promoting and
        // the tail shifting up. A request that becomes the head directly must not also land in the tail.
        val tailFromNew = acceptFire & headValid & (!promote | tailValid)
        when(tailFromNew) {
            tailValid := true.B
            tailAddr := io.top.req.bits.addr
            tailUser := io.top.req.bits.user
            tailId := io.top.req.bits.id
        }.otherwise { when(promote & tailValid) { tailValid := false.B } }

        // The stage's decision, once per request: a miss fills the slot the engine works from, a hit touches the
        // replacement tree. The promotion below overrides `t1Decided`'s set, so a request promoted in the same cycle
        // the previous one leaves starts undecided.
        val t1Settling = t1Valid & !t1Decided
        when(t1Settling) { t1Decided := true.B }
        when(t1Settling & t1Miss) {
            missPending := true.B
            missAddr := t1Addr
            missSet := t1.set
            missTag := t1Tag
            missWay := victim
            missWordOff := t1.word
            missCacheable := t1Cacheable
        }
        when(t1Settling & !t1Miss) {
            touchPending := true.B
            touchSet := t1.set
            touchWay := t1Way
        }.otherwise { touchPending := false.B }
        when(touchPending) { repl.touch(touchSet, touchWay) }

        // ── The pipeline advance: the head's read goes to the result stage, and a successor takes its place ──
        when(promote) {
            t1Valid := true.B
            t1Decided := false.B
            t1Addr := headAddr
            t1User := headUser
            t1Id := headId
            for way <- 0 until c.ways do
                t1Rows(way) := storage.io.rdRows.ref(way)
                t1Vd(way) := storage.io.vdBits.ref(way)
        }.otherwise { when(respFire) { t1Valid := false.B } }

        /** What the cache answers: how often the store answered and how often it had to go to memory. */
        def counters(): Unit =
            val hits = parameter.stats.stat("hit", CacheRegions.Cache, "fetches answered by the cache")
            val misses = parameter.stats.stat("miss", CacheRegions.Cache, "fetches the cache had to refill")
            val uncached = parameter.stats.stat("uncached", CacheRegions.Cache, "fetches outside the cacheable windows")
            hits.inc(t1Settling & t1Hit)
            misses.inc(t1Settling & t1Miss & t1Cacheable)
            uncached.inc(t1Settling & !t1Cacheable)

        if parameter.flow.counters then counters()

        // The memory side: the engine is the only master the port has, and a buffer sits between the two. The refill's
        // request valid is state, but the fabric's ready is not — wired straight through, the fabric's arbitration would
        // extend the engine's request path out of the cache. The elastic slice makes what the fabric sees state as well;
        // the response channel stays combinational. The port is read/write, so it has no flush — and it needs none: the
        // refill engine is a consumer that waits for its answer, so a dropped request would leave it waiting.
        io.bottom :<>= refill.io.bottom.buffered("bottomSlice", BufferPolicy.Slice)
