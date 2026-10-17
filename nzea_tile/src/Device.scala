package nzea_tile

import mainargs.TokensReader
import nzea_device.DeviceKind
import nzea_rtl.bus.{AddrRange, SlaveKind}
import nzea_rtl.bus.given

/** One device on the tile: its window and its kind.
  *
  * The tile parameter's device list, and the one description the address map, the IO and the wiring all derive from.
  * The `kind` ([[nzea_device.DeviceKind]]) is what knows how to build the device and what it shows at the boundary, so
  * adding a device is one case there plus its own module and one entry in the list — nothing else in the tile changes.
  */
case class DeviceSpec(name: String, range: AddrRange, kind: DeviceKind, outstanding: Int = 1)

object DeviceSpec:

    given upickle.default.ReadWriter[DeviceSpec] = upickle.default.macroRW

    /** The flag's spelling is one device's own JSON form, the same the map's serialization uses; a `Seq[DeviceSpec]`
      * flag is this repeated (`--devices <json> --devices <json>`).
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
  * above feeds the same nets to the tile and to its devices.
  *
  * `slaveKind` is the window's own statement (a memory window joins what a cache may refill from; an MMIO one joins the
  * commit trace's), and `outstanding` is how many requests the device promises to hold at once — no field has a
  * default, because both are facts about the device that no one else can supply.
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
