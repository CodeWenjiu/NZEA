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
  * The hit path is a two-stage pipeline that accepts one request per cycle: the head cycle reads the head's set on the
  * storage's *tag* port and compares — a narrow mux, so the compare never chains behind the line's — and the result
  * stage reads the same entry's set on the *row* port, where the way the compare settled picks the word. The head's
  * address comes from the queue's registers, so the compare's result goes into registers (the result stage, or the miss
  * slot), never back into an address.
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

        // ── The result stage: the entry whose answer is being formed, held until the fetch takes it ──
        val t1Valid = RegInit(false.B)
        val t1Miss = RegInit(false.B)
        val t1Addr = Reg(UInt(aw))
        val t1Way = Reg(UInt(wayBits))
        val t1User = Reg(UInt(parameter.userWidth))
        val t1Id = Reg(UInt(parameter.idWidth))

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

        // The answer's word: the row port reads the result stage's set, and the way the compare settled selects the
        // line the word comes from — a chain that starts after the compare, not through it.
        val t1Word = muxTree(t1Way, (0 until c.ways).map(w => lineOf(storage.io.rdRows.ref(w)).data.ref(addrOf(t1Addr).word)))
        val missReady = refill.io.done
        val respValid = t1Valid & (!t1Miss | missReady)
        io.top.resp.valid := respValid
        io.top.resp.bits.data := t1Miss ? (refill.io.bypassData, t1Word)
        io.top.resp.bits.user := t1User
        io.top.resp.bits.id := t1Id
        val respFire = respValid & io.top.resp.ready

        // The result stage will be free next cycle: the head's row may be handed over.
        val t1Free = !t1Valid | respFire
        val promote = headValid & t1Free

        // The head, live: the tag port reads its set this cycle, so the compare never chains behind the line's mux.
        val head = addrOf(headAddr)
        val headSet = head.set
        val headTag = head.tag
        val headWordOff = head.word
        val headCacheable = cacheableOf(headAddr)
        val headTags = (0 until c.ways).map(w => storage.io.rdTags.ref(w))
        val headHits = (0 until c.ways).map(w => storage.io.vdBits.ref(w) & (headTags(w) === headTag))
        val headHit = headHits.reduce(_ | _) & headCacheable
        val headWay = muxCase(0.U(wayBits), headHits.zipWithIndex.map((hit, w) => (hit, w.U(wayBits))))

        // The victim: a free way first, the replacement state only once the set is full.
        val freeWay = muxCase(0.U(wayBits), (0 until c.ways).map(w => (!storage.io.vdBits.ref(w), w.U(wayBits))))
        val hasFree = (0 until c.ways).map(w => !storage.io.vdBits.ref(w)).reduce(_ | _)
        val victim = hasFree ? (freeWay, repl.way(headSet))

        val accept = !headValid | !tailValid | promote
        io.top.req.ready := accept
        val acceptFire = io.top.req.valid & accept

        // ── The storage's two read ports ──
        // The tag port reads the head's set combinationally — the compare's own narrow path — and the row port reads the
        // result stage's set, whose line the settled way selects a word from. One address each, no chain between.
        storage.io.tagSet := headSet
        storage.io.rdSet := addrOf(t1Addr).set
        storage.io.rdEn := t1Valid
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

        when(promote) {
            t1Valid := true.B
            t1Miss := !headHit
            t1Addr := headAddr
            t1Way := headWay
            t1User := headUser
            t1Id := headId
        }.otherwise { when(respFire) { t1Valid := false.B } }

        when(promote & !headHit) {
            missPending := true.B
            missAddr := headAddr
            missSet := headSet
            missTag := headTag
            missWay := victim
            missWordOff := headWordOff
            missCacheable := headCacheable
        }
        when(promote & headHit) {
            touchPending := true.B
            touchSet := headSet
            touchWay := headWay
        }.otherwise { touchPending := false.B }
        when(touchPending) { repl.touch(touchSet, touchWay) }

        /** What the cache answers: how often the store answered and how often it had to go to memory. */
        def counters(): Unit =
            val hits = parameter.stats.stat("hit", CacheRegions.Cache, "fetches answered by the cache")
            val misses = parameter.stats.stat("miss", CacheRegions.Cache, "fetches the cache had to refill")
            val uncached = parameter.stats.stat("uncached", CacheRegions.Cache, "fetches outside the cacheable windows")
            hits.inc(promote & headHit)
            misses.inc(promote & !headHit & headCacheable)
            uncached.inc(promote & !headCacheable)

        if parameter.flow.counters then counters()

        // The memory side: the engine is the only master the port has, and a register slice sits between the two. The
        // refill's request valid is state, but the fabric's ready is not — wired straight through, the fabric's
        // arbitration would extend the engine's request path out of the cache. The slice makes what the fabric sees
        // state as well; the response channel stays combinational.
        val bottomSlice = LiteBusRegisterSlice.instantiate(
            LiteBusRegisterSliceParameter(aw, parameter.dataWidth, parameter.userWidth, parameter.idWidth)
        )
        bottomSlice.io.clock := io.clock
        bottomSlice.io.resetN := io.resetN
        bottomSlice.io.in :<>= refill.io.bottom
        io.bottom :<>= bottomSlice.io.out
