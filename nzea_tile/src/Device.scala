package nzea_tile

import mainargs.TokensReader
import nzea_device.DeviceKind
import nzea_rtl.bus.AddrRange
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
