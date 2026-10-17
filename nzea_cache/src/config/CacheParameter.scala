package nzea_cache.config

import me.jiuyang.zaozi.*
import nzea_rtl.RtlFlow
import nzea_rtl.bus.AddrRange
import nzea_rtl.bus.given
import nzea_rtl.stat.StatScope

/** What both caches are built from. The two differ in policy — what their top port is, what a store does — not in what
  * they are made of.
  */
case class CacheParameter(
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

object CacheParameter:

    given upickle.default.ReadWriter[CacheParameter] = upickle.default.macroRW
