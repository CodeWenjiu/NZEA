package nzea_cache

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.bank.{CacheBank, CacheBankParameter}
import nzea_cache.config.CacheParameter
import nzea_cache.path.{CacheQueue, CacheStage}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.*
import nzea_rtl.bus.given
import nzea_rtl.stat.{StatRegion, StatScope}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The counters the caches declare. */
object CacheRegions:
    val Cache = StatRegion("cache", "Cache")

class ICacheLayers(parameter: CacheParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ICacheIO(parameter: CacheParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The core side: the cache is its slave. */
    val top = Flipped(new LiteBusRO(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

    /** The fabric side: the cache is its master, the bank's engine behind a boundary of its own. */
    val bottom =
        Aligned(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class ICacheProbe(parameter: CacheParameter) extends DVBundle[CacheParameter, ICacheLayers](parameter)

/** The instruction cache: a [[CacheBank]] behind the fetch's own hit path, and nothing else.
  *
  * The path is the shared [[CacheQueue]]: requests queue up two deep, the head's read is captured into the result
  * stage, and the stage compares the captured tags — a miss hands its address to the bank, which fills the line from
  * memory: the miss's word comes back the moment its own beat lands, and a request of the same line staged behind it
  * rides the same fill until the line installs. A request outside the cacheable windows is a miss the bank serves with
  * a single beat and installs nothing of.
  *
  * What is this cache's own is the port it answers on: a read-only top (the type says it never writes, so no store
  * rides along), a load-only answer, and the boundary that keeps the fabric's ready out of the pipeline.
  */
@generator
object ICache extends Generator[CacheParameter, ICacheLayers, ICacheIO, ICacheProbe]:
    override def moduleName(parameter: CacheParameter): String = "ICache"

    def architecture(parameter: CacheParameter) =
        val io = summon[Interface[ICacheIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val aw = parameter.addrWidth
        val idxBits = c.indexBits
        val wayBits = c.wayBits
        val rowWidth = c.rowWidth(aw)

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
        val queue = new CacheQueue(c, aw)(new LiteReqRO(aw, parameter.userWidth, parameter.idWidth))
        val stage = new CacheStage(c, aw, parameter.dataWidth, parameter.cacheable)(
            queue.stageReq.addr,
            queue.stageRows,
            queue.stageVd
        )

        // ── The bank's read, and the stage's decisions ──
        bank.io.rdAddr := queue.headReq.addr
        bank.io.rdEn := queue.headValid
        bank.io.vd :<= queue.stageVd
        bank.io.stageAddr := queue.stageReq.addr
        bank.io.miss := queue.stageSettling & stage.miss
        bank.io.missCacheable := stage.cacheableHit
        bank.io.hit := queue.stageSettling & stage.hit
        bank.io.hitWay := stage.way
        bank.io.engineFree := true.B
        // The instruction path never writes the arrays.
        bank.io.wrValid := false.B
        bank.io.wrSet := 0.U(idxBits)
        bank.io.wrWay := 0.U(wayBits)
        bank.io.wrData := 0.U(rowWidth)

        // The answer leaves on the port, and the handover below — `promote`, hence `accept` — reads the port's ready.
        // That ready is the consumer's, but the consumer's own boundary buffers it (the core's bus boundary,
        // [[responseConnect]]): what this port sees is a buffer's count, not the pipeline's back-pressure cone. The
        // refill's ack follows the handover, not the consumer's take.
        val missReady = bank.io.fillReady
        val respValid = queue.stageValid & (!stage.miss | missReady)
        io.top.resp.valid := respValid
        io.top.resp.bits.data := stage.miss ? (bank.io.fillData, stage.word)
        io.top.resp.bits.user := queue.stageReq.user
        io.top.resp.bits.id := queue.stageReq.id
        val respFire = respValid & io.top.resp.ready
        bank.io.ack := stage.miss & respFire

        // The result stage will be free next cycle: the head's row may be handed over.
        val promote = queue.headValid & (!queue.stageValid | respFire)
        io.top.req.ready := queue.accept(promote)
        queue.advance(promote, respFire, io.top.req.valid, io.top.req.bits, bank.io.rdRows, bank.io.vdBits)

        /** What the cache answers: how often the store answered and how often it had to go to memory. */
        def counters(): Unit =
            val hits = parameter.stats.stat("hit", CacheRegions.Cache, "fetches answered by the cache")
            val misses = parameter.stats.stat("miss", CacheRegions.Cache, "fetches the cache had to refill")
            val uncached = parameter.stats.stat("uncached", CacheRegions.Cache, "fetches outside the cacheable windows")
            hits.inc(queue.stageSettling & stage.hit)
            // A miss served from the line already arriving did not go to memory: only the one that starts a refill counts.
            misses.inc(queue.stageSettling & stage.miss & stage.cacheableHit & !bank.io.fillMatch)
            uncached.inc(queue.stageSettling & !stage.cacheableHit)

        if parameter.flow.counters then counters()

        // The memory side: the bank's engine is the only master the port has, and a buffer sits between the two. The
        // engine's request valid is state, but the fabric's ready is not — wired straight through, the fabric's
        // arbitration would extend the engine's request path out of the cache. The elastic slice makes what the fabric
        // sees state as well; the response channel stays combinational. The port is read/write, so it has no flush —
        // and it needs none: the engine is a consumer that waits for its answer, so a dropped request would leave it
        // waiting.
        io.bottom :<>= bank.io.bottom.buffered("bottomSlice", BufferPolicy.Slice)
