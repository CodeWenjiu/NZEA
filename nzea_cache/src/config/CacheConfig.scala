package nzea_cache.config

import mainargs.TokensReader
import nzea_rtl.stdlib.{log2Ceil, SyncRamPorts}

/** Which way a full set victimizes: what the replacement state answers with when no way is free. */
enum Replacement(val name: String):
    case Plru extends Replacement("plru")
    case Random extends Replacement("random")

object Replacement:

    given upickle.default.ReadWriter[Replacement] =
        upickle.default.readwriter[String].bimap(_.name, parseOrThrow)

    def parse(token: String): Either[String, Replacement] =
        token.trim.toLowerCase match
            case "plru"   => Right(Plru)
            case "random" => Right(Random)
            case other    => Left(s"unknown replacement '$other'; known: plru, random")

    def parseOrThrow(token: String): Replacement =
        parse(token).fold(error => throw new IllegalArgumentException(error), identity)

    given TokensReader.Simple[Replacement] with
        def shortName = "plru|random"

        def read(strs: Seq[String]): Either[String, Replacement] =
            strs.lastOption.map(parse).getOrElse(Left("replacement requires a value"))

/** What a cache's arrays are built from.
  *
  * A real L1 is SRAM, but it is a macro shaped for it — few rows, short bit lines, one full-clock access. A platform
  * whose only macro is a deep, density-sized one (`1rw` / `1r1w` here) can build the same cache from flops instead:
  * larger, but fast, and the control is identical because the interface below is only a synchronous read and a write.
  * The choice is the platform's, stated in the config, so a better macro later is a different value here.
  */
enum CacheStorageKind(val name: String):
    /** The arrays are flops: the read is a mux of the addressed set, the write one decoded row. */
    case Registers extends CacheStorageKind("registers")

    /** The arrays are synchronous memories with this port shape. */
    case Macro(ports: SyncRamPorts) extends CacheStorageKind(ports.name)

object CacheStorageKind:

    given upickle.default.ReadWriter[CacheStorageKind] =
        upickle.default.readwriter[String].bimap(_.name, parseOrThrow)

    def parse(token: String): Either[String, CacheStorageKind] =
        SyncRamPorts.parse(token) match
            case Right(ports)                                     => Right(Macro(ports))
            case Left(_) if token.trim.toLowerCase == "registers" => Right(Registers)
            case Left(_) => Left(s"unknown storage '$token'; known: registers, 1rw, 1r1w")

    def parseOrThrow(token: String): CacheStorageKind =
        parse(token).fold(error => throw new IllegalArgumentException(error), identity)

    given TokensReader.Simple[CacheStorageKind] with
        def shortName = "registers|1rw|1r1w"

        def read(strs: Seq[String]): Either[String, CacheStorageKind] =
            strs.lastOption.map(parse).getOrElse(Left("storage requires a value"))

/** A set-associative cache's geometry: its sets, ways, line size, victim policy and storage kind.
  *
  * The storage kind is the platform's, not the cache's: the control never reads and writes the storage in the same
  * cycle, so a single read-write macro port (`1rw`) fits as well as separate ones (`1r1w`), and flops fit both — which
  * one is built is stated here by whoever knows the platform.
  *
  * The storage row is `padding ## tag ## line`: the tag sits above the line, so its position is the same for every
  * geometry, and a macro-backed row is padded to whole bytes because a single-port macro is byte-strobed.
  */
case class CacheConfig(
    sets: Int,
    ways: Int,
    lineBytes: Int,
    replacement: Replacement,
    storage: CacheStorageKind
):
    require(sets >= 2 && (sets & (sets - 1)) == 0, s"sets must be a power of 2 >= 2, got $sets")
    require(ways >= 2 && (ways & (ways - 1)) == 0, s"ways must be a power of 2 >= 2, got $ways")

    require(
        lineBytes >= 4 && (lineBytes & (lineBytes - 1)) == 0,
        s"lineBytes must be a power of 2 >= 4, got $lineBytes"
    )

    require(
        storage != CacheStorageKind.Macro(SyncRamPorts.OneR1RW) &&
            storage != CacheStorageKind.Macro(SyncRamPorts.OneW1RW) &&
            storage != CacheStorageKind.Macro(SyncRamPorts.TwoRW),
        s"the storage macro's port shape is one of 1rw, 1r1w, got ${storage.name}"
    )

    def indexBits: Int = log2Ceil(sets)
    def offsetBits: Int = log2Ceil(lineBytes)
    def wayBits: Int = log2Ceil(ways)
    def lineBits: Int = lineBytes * 8

    /** Tag bits for one address width: everything above the set index and the line offset. */
    def tagBits(addrWidth: Int): Int = addrWidth - indexBits - offsetBits

    /** Beats one line takes on a bus of this data width. */
    def lineBeats(dataWidth: Int): Int = lineBytes / (dataWidth / 8)

    /** Bits selecting the word inside a line on a bus of this data width. */
    def wordOffBits(dataWidth: Int): Int = offsetBits - log2Ceil(dataWidth / 8)

    /** The storage row's width for one address width: the padded tag above the line. */
    def rowWidth(addrWidth: Int): Int =
        val bits = tagBits(addrWidth) + lineBits
        if storage == CacheStorageKind.Macro(SyncRamPorts.OneRW) then (bits + 7) / 8 * 8 else bits

    /** The config's spelling: `sets x ways x lineBytes`, the victim policy, the storage kind. */
    def name: String = s"${sets}x${ways}x${lineBytes}:${replacement.name}:${storage.name}"

object CacheConfig:

    given upickle.default.ReadWriter[CacheConfig] =
        upickle.default.readwriter[String].bimap(_.name, parseOrThrow)

    def parse(token: String): Either[String, CacheConfig] =
        token.trim.toLowerCase.split(":") match
            case Array(geometry, replacement, storage) =>
                geometry.split("x") match
                    case Array(sets, ways, lineBytes) =>
                        for
                            s <- sets.toIntOption.toRight(s"sets must be a number, got '$sets'")
                            w <- ways.toIntOption.toRight(s"ways must be a number, got '$ways'")
                            l <- lineBytes.toIntOption.toRight(s"lineBytes must be a number, got '$lineBytes'")
                            r <- Replacement.parse(replacement)
                            k <- CacheStorageKind.parse(storage)
                            config <- scala.util.Try(CacheConfig(s, w, l, r, k)).toEither.left.map(_.getMessage)
                        yield config
                    case _ => Left(s"geometry wants 'setsxwaysxlineBytes', got '$geometry'")
            case _ => Left(s"expected 'setsxwaysxlineBytes:replacement:storage', got '$token'")

    def parseOrThrow(token: String): CacheConfig =
        parse(token).fold(error => throw new IllegalArgumentException(error), identity)

    /** The generator framework's reader: the flag takes the same token [[parse]] takes. The parameter's field is an
      * `Option`, so an absent flag is a cache that is not built.
      */
    given TokensReader.Simple[CacheConfig] with
        def shortName = "setsxwaysxlineBytes:plru|random:registers|1rw|1r1w"

        def read(strs: Seq[String]): Either[String, CacheConfig] =
            strs.lastOption.map(parse).getOrElse(Left("cache requires a value"))

    /** A 4 KiB 4-way baseline with 16-byte lines, PLRU, and the platform's storage kind. */
    def baseline(storage: CacheStorageKind): CacheConfig =
        CacheConfig(sets = 64, ways = 4, lineBytes = 16, replacement = Replacement.Plru, storage = storage)
