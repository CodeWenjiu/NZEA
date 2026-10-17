package nzea_cache

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.bank.{CacheBank, CacheBankParameter}
import nzea_cache.config.CacheParameter
import nzea_cache.path.{CacheLookup, CacheQueue, CacheStage}
import nzea_rtl.bus.*
import nzea_rtl.bus.given
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

class DCacheLayers(parameter: CacheParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class DCacheIO(parameter: CacheParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The core side: the cache is its slave, and the data path both reads and writes. */
    val top = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

    /** The fabric side: the cache is its master — the bank's engine and the stores' write-through, one port. */
    val bottom =
        Aligned(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class DCacheProbe(parameter: CacheParameter) extends DVBundle[CacheParameter, DCacheLayers](parameter)

/** The data cache: a [[CacheBank]] behind the load/store path's hit path, written through.
  *
  * A store updates the hit way's row — byte by byte, by the strobe — and leaves for memory in the same breath; a store
  * that misses allocates nothing, so a line is never dirty and the bank's engine stays the read-only one the instruction
  * cache uses too. The slot holds a store until its array update has landed, and a store that missed departs on its write
  * alone: that ordering is also why the whole cache is in-order — a load behind a store cannot reach the fabric before
  * the store's write does, and the core keeps no store-to-load forwarding of its own.
  */
@generator
object DCache extends Generator[CacheParameter, DCacheLayers, DCacheIO, DCacheProbe]:
    override def moduleName(parameter: CacheParameter): String = "DCache"

    def architecture(parameter: CacheParameter) =
        val io = summon[Interface[DCacheIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val aw = parameter.addrWidth
        val idxBits = c.indexBits
        val wayBits = c.wayBits
        val tagBits = c.tagBits(aw)
        val lineBeats = c.lineBeats(parameter.dataWidth)
        val wordOffBits = c.wordOffBits(parameter.dataWidth)
        val wordBits = parameter.dataWidth
        val strbBits = parameter.dataWidth / 8
        val rowWidth = c.rowWidth(aw)
        val padBits = rowWidth - tagBits - c.lineBits

        val bank = CacheBank.instantiate(
            CacheBankParameter(
                aw,
                parameter.dataWidth,
                parameter.userWidth,
                parameter.idWidth,
                c,
                parameter.outstanding
            )
        )
        bank.io.clock := io.clock
        bank.io.resetN := io.resetN
        val queue = new CacheQueue(c, aw)(new LiteReq(aw, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
        val stage = new CacheStage(c, aw, parameter.dataWidth, parameter.cacheable)(
            queue.stageReq.addr,
            queue.stageRows,
            queue.stageVd
        )

        val t1StoreSent = RegInit(false.B)
        val storeWritePending = RegInit(false.B)
        val storeWriteLanded = RegInit(false.B)
        val t1Store = queue.stageReq.wen

        // The acknowledgments in flight: one per store write the fabric has taken and not yet answered. The bound is
        // the fabric's own per-slave capacity, and the gate on it is the counter's own safety — a wrapped count would
        // let a beat be mistaken for an acknowledgment.
        val ackBits = 6
        val storeAcks = RegInit(0.U(ackBits))
        val storeRoom = storeAcks < 32.U(ackBits)

        // Its write-through leaves the slot as soon as the bus takes it; what the slot waits for after that is its own
        // array update, because the storage's write is registered twice and a successor's read must not sample the row
        // the update is about to replace. A store that missed allocates nothing, so it departs on its write alone.
        val storeUpdate = queue.stageSettling & t1Store & stage.hit
        val storeDone = queue.stageValid & t1Store & t1StoreSent & (storeWriteLanded | !stage.hit)

        // The load's answer: a store has none — its completion is its write's acceptance at the port above.
        val missReady = bank.io.fillReady
        val respValid = queue.stageValid & !t1Store & (!stage.miss | missReady)
        io.top.resp.valid := respValid
        io.top.resp.bits.data := stage.miss ? (bank.io.fillData, stage.word)
        io.top.resp.bits.user := queue.stageReq.user
        io.top.resp.bits.id := queue.stageReq.id
        val respFire = respValid & io.top.resp.ready

        // A store never fills the miss slot — its miss allocates nothing — and its hit touches the tree like any other
        // use of the line.
        bank.io.rdAddr := queue.headReq.addr
        bank.io.rdEn := queue.headValid
        bank.io.vd :<= queue.stageVd
        bank.io.stageAddr := queue.stageReq.addr
        bank.io.miss := queue.stageSettling & stage.miss & !t1Store
        bank.io.missCacheable := stage.cacheableHit
        bank.io.hit := queue.stageSettling & stage.hit
        bank.io.hitWay := stage.way
        bank.io.ack := stage.miss & respFire
        // The engine may start once every store's acknowledgment has drained: a refill's beats and a store's
        // acknowledgment share the one response channel below, and the engine leaves `run` with all of its beats
        // received, so an acknowledgment in flight would be counted as one.
        bank.io.engineFree := storeAcks === 0.U(ackBits)

        // The strobe's bytes take the write data and the rest keep what the row holds, because the bus word's unstrobed
        // lanes are the store's own shifted value, not the memory's bytes.
        val storeWayLine = CacheLookup.lineOf(c, aw, parameter.dataWidth)(queue.stageRows.ref(stage.way))
        val oldWord = Node(storeWayLine.data.ref(stage.view.word).asBits)
        val wdataBits = Node(queue.stageReq.wdata.asBits)
        val wstrbBits = Node(queue.stageReq.wstrb.asBits)
        val mergedWord =
            (strbBits - 1 to 0 by -1)
                .map(b => wstrbBits.bit(b) ? (wdataBits.bits(b * 8 + 7, b * 8), oldWord.bits(b * 8 + 7, b * 8)))
                .reduce(_ ## _)
                .asUInt
        val storeRow = Wire(new CacheLine(tagBits, lineBeats, wordBits))
        storeRow.tag := stage.tag
        for i <- 0 until lineBeats do
            storeRow.data.ref(i) :=
                (stage.view.word === i.U(wordOffBits)) ? (mergedWord, storeWayLine.data.ref(i))
        val storeRowBits = if padBits > 0 then storeRow.asBits.pad(rowWidth).asUInt else storeRow.asBits.asUInt

        bank.io.wrValid := storeUpdate
        bank.io.wrSet := stage.view.set
        bank.io.wrWay := stage.way
        bank.io.wrData := storeRowBits

        // The result stage will be free next cycle: the head's row may be handed over. A store's row read decides its
        // update, and one racing the install would miss the line — the store would skip its update, leaving the memory's
        // copy — so a store head waits for the engine to go idle; the gate sits in `promote`, so ready and advance agree.
        val promote = queue.headValid & (!queue.stageValid | respFire | storeDone) &
            (queue.headReq.wen ? (!bank.io.busy, true.B))
        io.top.req.ready := queue.accept(promote)
        queue.advance(
            promote,
            respFire | storeDone,
            io.top.req.valid,
            io.top.req.bits,
            bank.io.rdRows,
            bank.io.vdBits
        )

        def counters(): Unit =
            val hits = parameter.stats.stat("hit", CacheRegions.Cache, "loads answered by the cache")
            val misses = parameter.stats.stat("miss", CacheRegions.Cache, "loads the cache had to refill")
            val uncached = parameter.stats.stat("uncached", CacheRegions.Cache, "loads outside the cacheable windows")
            val stores = parameter.stats.stat("store", CacheRegions.Cache, "stores written through")
            hits.inc(queue.stageSettling & stage.hit & !t1Store)
            // A miss served from the line already arriving did not go to memory: only the one that starts a refill counts.
            misses.inc(queue.stageSettling & stage.miss & stage.cacheableHit & !t1Store & !bank.io.fillMatch)
            uncached.inc(queue.stageSettling & !stage.cacheableHit & !t1Store)
            stores.inc(queue.stageSettling & t1Store)

        if parameter.flow.counters then counters()

        // The request channel gets one elastic entry, so what the fabric arbitrates on is a register rather than the
        // caches' own control; the response channel stays combinational — its consumers, the engine and the
        // acknowledgment count, both drain.
        val near = Wire(new LiteBusRW(aw, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
        val far = Wire(new LiteBusRW(aw, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
        bufferConnect(near.req, far.req, BufferPolicy.Slice, "bottomSlice", None)
        near.resp.valid := far.resp.valid
        near.resp.bits :<>= far.resp.bits
        far.resp.ready := near.resp.ready
        io.bottom :<>= far

        // The producers: the engine's beats while it runs, a store's write-through otherwise. The store's request is
        // held until the buffer takes it — that acceptance is what its slot waits for.
        val engineBusy = bank.io.busy
        val engineRunning = bank.io.bottom.resp.ready
        val storeReq = queue.stageValid & t1Store & !t1StoreSent & !engineBusy & storeRoom
        near.req.valid := bank.io.bottom.req.valid | storeReq
        near.req.bits.addr := engineBusy ? (bank.io.bottom.req.bits.addr, queue.stageReq.addr)
        near.req.bits.wdata := engineBusy ? (bank.io.bottom.req.bits.wdata, queue.stageReq.wdata)
        near.req.bits.wen := engineBusy ? (bank.io.bottom.req.bits.wen, true.B)
        near.req.bits.wstrb := engineBusy ? (bank.io.bottom.req.bits.wstrb, queue.stageReq.wstrb)
        near.req.bits.user := engineBusy ? (bank.io.bottom.req.bits.user, queue.stageReq.user)
        near.req.bits.id := engineBusy ? (bank.io.bottom.req.bits.id, queue.stageReq.id)
        bank.io.bottom.req.ready := near.req.ready
        val storeFire = storeReq & near.req.ready

        bank.io.bottom.resp.valid := near.resp.valid & engineRunning
        bank.io.bottom.resp.bits :<= near.resp.bits
        near.resp.ready := engineRunning | (storeAcks =/= 0.U(ackBits))
        val ackFire = near.resp.valid & near.resp.ready & !engineRunning

        // The count: a write handed over raises it, an acknowledgment lowers it, and the two in one cycle net out.
        storeAcks := addWrap(
            storeAcks,
            subWrap(storeFire ? (1.U(ackBits), 0.U(ackBits)), ackFire ? (1.U(ackBits), 0.U(ackBits)), ackBits),
            ackBits
        )

        // The update's landing: commanded once, in the settling cycle, and one cycle later the array holds the new row.
        // Everything retires with the request — a successor's promotion, or the slot emptying — and the retirement's
        // clear wins over an advance of the same cycle.
        when(storeUpdate) { storeWritePending := true.B }
        when(storeWritePending) { storeWriteLanded := true.B }
        when(storeFire) { t1StoreSent := true.B }
        val t1Retire = promote | respFire | storeDone
        when(t1Retire) {
            t1StoreSent := false.B
            storeWritePending := false.B
            storeWriteLanded := false.B
        }
