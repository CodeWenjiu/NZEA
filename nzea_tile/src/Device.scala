package nzea_tile

import java.lang.foreign.Arena

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.{CsrType, Gpr}
import nzea_device.clint.{ClintParameter, ClintPins}
import nzea_device.finisher.{FinisherPins, SifiveTestFinisher, SifiveTestFinisherParameter}
import nzea_device.ram.{RamFabricSlave, RamFabricSlaveParameter}
import nzea_device.uart.UartParameter
import nzea_rtl.RtlFlow
import nzea_rtl.bus.*
import nzea_rtl.bus.given
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What a device needs to be built: the bus widths and the window it answers. */
case class DeviceContext(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int, base: Long, size: Long)

/** One device on the tile: its window and its kind.
  *
  * The tile parameter's device list, and the one description the address map, the IO and the wiring all derive from.
  * The `kind` is what knows how to build the device and what it shows at the boundary, so adding a device is one case
  * there plus its own module and one entry in the list — nothing else in the tile changes.
  */
case class DeviceSpec(name: String, range: AddrRange, kind: DeviceKind, outstanding: Int = 1)

/** The kinds of device the tile can place: the one place that knows what a device is on the address map and how to
  * build it, so the tile's wiring is a `kind` match instead of a hand-kept name list.
  */
enum DeviceKind:
    case Ram, Uart, Finisher, Clint

    /** What it is on the address map: a memory window, or an MMIO register window with side effects. */
    def slaveKind: SlaveKind = this match
        case Ram => SlaveKind.Memory
        case _   => SlaveKind.Mmio

    /** The pins this kind shows at the tile boundary for a flow; `None` when it shows none — on-chip storage, a device
      * whose hardware is not ported, or the bridge flow, where the simulator answers it through DPI.
      */
    def pins(flow: RtlFlow)(using TypeImpl, ConstructorApi): Option[Bundle] = this match
        case Finisher => if flow.bridges then None else Some(new FinisherPins)
        case Clint    => if flow.bridges then None else Some(new ClintPins)
        case _        => None

    /** Build the device for its window. */
    def instantiate(flow: RtlFlow, ctx: DeviceContext)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[?, ?] = this match
        case Ram =>
            RamFabricSlave.instantiate(
                RamFabricSlaveParameter(
                    ctx.addrWidth,
                    ctx.dataWidth,
                    ctx.userWidth,
                    ctx.idWidth,
                    ctx.base,
                    ctx.size,
                    flow
                )
            )
        case Uart =>
            nzea_device.uart.Uart.instantiate(
                UartParameter(ctx.addrWidth, ctx.dataWidth, ctx.userWidth, ctx.idWidth, ctx.base, flow)
            )
        case Finisher =>
            SifiveTestFinisher.instantiate(
                SifiveTestFinisherParameter(ctx.addrWidth, ctx.dataWidth, ctx.userWidth, ctx.idWidth, flow)
            )
        case Clint =>
            nzea_device.clint.Clint.instantiate(
                ClintParameter(ctx.addrWidth, ctx.dataWidth, ctx.userWidth, ctx.idWidth, ctx.base, flow)
            )

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

given upickle.default.ReadWriter[DeviceKind] =
    upickle.default.readwriter[String].bimap(_.toString, name => DeviceKind.valueOf(name))
