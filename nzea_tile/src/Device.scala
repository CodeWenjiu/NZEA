package nzea_tile

import mainargs.TokensReader
import nzea_device.DeviceKind
import nzea_rtl.bus.{AddrRange, SlaveKind}
import nzea_rtl.bus.given

/** One device on the tile: its window and its kind, the one description the address map, the IO and the wiring all
  * derive from. The kind ([[nzea_device.DeviceKind]]) is what builds the device and says what it shows at the boundary,
  * so adding a device is one case there plus one entry in the list.
  */
case class DeviceSpec(name: String, range: AddrRange, kind: DeviceKind, outstanding: Int = 1)

object DeviceSpec:

    given upickle.default.ReadWriter[DeviceSpec] = upickle.default.macroRW

    /** The flag's spelling is one device's own JSON form, the same the map's serialization uses; a `Seq[DeviceSpec]`
      * flag is this repeated (`--externals <json> --externals <json>`).
      */
    given TokensReader.Simple[DeviceSpec] with
        def shortName = "device-json"

        def read(strs: Seq[String]): Either[String, DeviceSpec] =
            strs.lastOption.toRight("a device requires a value").flatMap { text =>
                scala.util.Try(upickle.default.read[DeviceSpec](text)).toEither.left.map(_.getMessage)
            }

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
