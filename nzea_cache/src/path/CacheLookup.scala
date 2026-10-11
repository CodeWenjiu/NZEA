package nzea_cache.path

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.{CacheAddr, CacheLine}
import nzea_cache.config.CacheConfig
import nzea_rtl.bus.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One request's whole lookup, computed once: the address's own fields, the per-way hits, the way and the word.
  *
  * The caller takes the address out of its payload — this side never looks inside one — and hands the captured read
  * with it; everything the stage's decision and the answer need hangs off the result.
  */
class CacheStage(c: CacheConfig, addrWidth: Int, dataWidth: Int, cacheable: Seq[AddrRange])(
    addr: Referable[UInt],
    rows: Referable[Vec[UInt]],
    vd: Referable[Vec[Bool]]
)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
):
    val view = CacheLookup.addrOf(c, addrWidth, dataWidth)(addr)
    val tag = view.tag
    val cacheableHit = CacheLookup.cacheableOf(c, addrWidth, cacheable)(addr)
    val hits = CacheLookup.hitsOf(c, addrWidth, dataWidth)(rows, vd, tag)
    val hit = hits.reduce(_ | _) & cacheableHit
    val miss = !hit
    val way = CacheLookup.wayOf(c)(hits)
    val word = CacheLookup.wordOf(c, addrWidth, dataWidth)(rows, way, view.word)

/** The lookup every cache in front of a bus shares: a storage row read as the line it holds, and the compare over a
  * captured set — which way's valid tag matches, which way that is, and the addressed word.
  *
  * The read is the caller's: this is the combinational half of a hit, so it works off whatever the cache captured (the
  * instruction cache's result stage holds the rows and the valids) and hands back what the caller's own control needs —
  * the per-way hits for the replacement decision, the way for the victim and the word for the answer.
  *
  * The name given is deliberate: a function's nodes are named by the `sourcecode.Name.Machine` in scope where they are
  * built, so the two that only fold values (`lineOf`, `hitsOf`) leave it to resolve inside themselves — the same names
  * a cache's own local helpers had — while the two that select (`wayOf`, `wordOf`) forward the caller's, so the muxes
  * carry the name of the val the cache bound them to.
  */
object CacheLookup:

    /** An address as the cache decomposes it: the tag above the set, the word inside the line below it, the byte at the
      * bottom.
      */
    def addrOf(c: CacheConfig, addrWidth: Int, dataWidth: Int)(a: Referable[UInt])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        InstanceContext
    ): Node[CacheAddr] =
        Node(a.asBits).asType(new CacheAddr(addrWidth, c.indexBits, c.offsetBits, log2Ceil(dataWidth / 8)))

    /** The address of the line an address's tag and set name, the offset cleared. */
    def lineBase(c: CacheConfig)(a: Node[CacheAddr])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        InstanceContext
    ): Node[UInt] = (a.tag.asBits ## a.set.asBits ## 0.B(c.offsetBits)).asUInt

    /** The address of the word an address names, the byte offset cleared. */
    def wordAligned(c: CacheConfig, dataWidth: Int)(a: Node[CacheAddr])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        InstanceContext
    ): Node[UInt] = (a.tag.asBits ## a.set.asBits ## a.word.asBits ## 0.B(log2Ceil(dataWidth / 8))).asUInt

    /** Whether an address falls in one of the windows the cache answers. */
    def cacheableOf(c: CacheConfig, addrWidth: Int, cacheable: Seq[AddrRange])(a: Referable[UInt])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        InstanceContext
    ): Node[Bool] =
        cacheable.map(r => (a >= r.base.U(addrWidth)) & (a < r.endExclusive.U(addrWidth))).reduce(_ | _)

    /** One storage row as the line it holds: the macro's byte padding stripped, the tag above the words. */
    def lineOf(
        c: CacheConfig,
        addrWidth: Int,
        dataWidth: Int
    )(
        row: Referable[UInt]
    )(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        InstanceContext
    ): Node[CacheLine] =
        val rowWidth = c.rowWidth(addrWidth)
        val padBits = rowWidth - c.tagBits(addrWidth) - c.lineBits
        val bits = Node(row.asBits)
        (if padBits > 0 then bits.bits(rowWidth - 1, padBits) else bits)
            .asType(new CacheLine(c.tagBits(addrWidth), c.lineBeats(dataWidth), dataWidth))

    /** The per-way hit compare of a captured set: the way's valid bit and the row's tag against the address's. */
    def hitsOf(
        c: CacheConfig,
        addrWidth: Int,
        dataWidth: Int
    )(
        rows: Referable[Vec[UInt]],
        vd: Referable[Vec[Bool]],
        tag: Referable[UInt]
    )(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        InstanceContext
    ): Seq[Node[Bool]] =
        (0 until c.ways).map(w => vd.ref(w) & (lineOf(c, addrWidth, dataWidth)(rows.ref(w)).tag === tag))

    /** The way a hit set names, for the victim's bookkeeping: the first (ways are exclusive) or none. */
    def wayOf(c: CacheConfig)(hits: Seq[Referable[Bool]])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Referable[UInt] = muxCase(0.U(c.wayBits), hits.zipWithIndex.map((hit, w) => (hit, w.U(c.wayBits))))

    /** The addressed word of the way the hit set names. */
    def wordOf(
        c: CacheConfig,
        addrWidth: Int,
        dataWidth: Int
    )(
        rows: Referable[Vec[UInt]],
        way: Referable[UInt],
        word: Referable[UInt]
    )(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] =
        muxTree(way, (0 until c.ways).map(w => lineOf(c, addrWidth, dataWidth)(rows.ref(w)).data.ref(word)))
