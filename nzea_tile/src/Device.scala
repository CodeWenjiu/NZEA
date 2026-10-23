package nzea_tile

import mainargs.TokensReader
import nzea_rtl.bus.{AddrRange, SlaveKind}
import nzea_rtl.bus.given

/** One window the level above answers: its name, its window, and the two facts the map needs.
  *
  * Nothing is built here: the fabric's slave port for the window leaves the tile as a boundary port of the tile's own
  * name ([[NzeaTile]]), and the level above connects its own device there. That device must run in the tile's own clock
  * and reset domain — the bus carries neither, and the tile hands a device both only when it builds it — so the level
  * above feeds the same nets to the tile and to its devices. `slaveKind` says what the window joins (a memory one joins
  * what a cache may refill from, an MMIO one the commit trace's) and `outstanding` how many requests the device holds
  * at once.
  */
case class ExternalSpec(name: String, range: AddrRange, slaveKind: SlaveKind, outstanding: Int)

object ExternalSpec:

    given upickle.default.ReadWriter[ExternalSpec] = upickle.default.macroRW

    /** The flag's spelling is one window's own JSON form; a `Seq[ExternalSpec]` flag is this repeated (`--externals
      * <json> --externals <json>`).
      */
    given TokensReader.Simple[ExternalSpec] with
        def shortName = "external-json"

        def read(strs: Seq[String]): Either[String, ExternalSpec] =
            strs.lastOption.toRight("an external window requires a value").flatMap { text =>
                scala.util.Try(upickle.default.read[ExternalSpec](text)).toEither.left.map(_.getMessage)
            }
