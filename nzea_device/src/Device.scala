package nzea_device

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.devices.{
    ClintParameter,
    ClintPins,
    FinisherPins,
    RamFabricSlave,
    RamFabricSlaveParameter,
    SifiveTestFinisher,
    SifiveTestFinisherParameter,
    UartParameter
}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.*
import nzea_rtl.bus.given
import nzea_rtl.stdlib.SyncRamPorts
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What a device needs to be built: the bus widths and the window it answers. */
case class DeviceContext(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int, base: Long, size: Long)

/** The kinds of device the library ships: the one place that knows what a device is on the address map and how to build
  * it, so a caller's wiring is a `kind` match instead of a hand-kept name list.
  *
  * The catalog lives with the devices it names — a new device is its own module plus one case here, and no layer above
  * changes. The port shape a kind carries is the target macro's ([[SyncRamPorts]]), stated by the caller's device list
  * rather than baked into the device, so a different macro is a different entry.
  */
enum DeviceKind:
    /** On-chip storage. */
    case Ram(ports: SyncRamPorts)
    case Uart
    case Finisher
    case Clint

    /** What it is on the address map: a memory window, or an MMIO register window with side effects. */
    def slaveKind: SlaveKind = this match
        case Ram(_) => SlaveKind.Memory
        case _      => SlaveKind.Mmio

    /** The pins this kind shows at the boundary for a flow; `None` when it shows none — on-chip storage, a device whose
      * hardware is not ported, or the bridge flow, where the simulator answers it through DPI.
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
        case Ram(ports) =>
            RamFabricSlave.instantiate(
                RamFabricSlaveParameter(
                    ctx.addrWidth,
                    ctx.dataWidth,
                    ctx.userWidth,
                    ctx.idWidth,
                    ctx.base,
                    ctx.size,
                    ports,
                    flow
                )
            )
        case Uart =>
            // Fully qualified: inside the enum, `Uart` is the case, and the case inherits this very method.
            nzea_device.devices.Uart.instantiate(
                UartParameter(ctx.addrWidth, ctx.dataWidth, ctx.userWidth, ctx.idWidth, ctx.base, flow)
            )
        case Finisher =>
            SifiveTestFinisher.instantiate(
                SifiveTestFinisherParameter(ctx.addrWidth, ctx.dataWidth, ctx.userWidth, ctx.idWidth, flow)
            )
        case Clint =>
            // Fully qualified for the same reason as `Uart`.
            nzea_device.devices.Clint.instantiate(
                ClintParameter(ctx.addrWidth, ctx.dataWidth, ctx.userWidth, ctx.idWidth, ctx.base, flow)
            )

object DeviceKind:

    /** One token per kind, `Ram:<shape>` for storage: the CLI flag's spelling and the config file's, one spelling for a
      * kind across both.
      */
    given upickle.default.ReadWriter[DeviceKind] =
        upickle.default
            .readwriter[String]
            .bimap(
                {
                    case DeviceKind.Ram(ports) => s"Ram:${ports.name}"
                    case DeviceKind.Uart       => "Uart"
                    case DeviceKind.Finisher   => "Finisher"
                    case DeviceKind.Clint      => "Clint"
                },
                {
                    case "Uart"                    => DeviceKind.Uart
                    case "Finisher"                => DeviceKind.Finisher
                    case "Clint"                   => DeviceKind.Clint
                    case s if s.startsWith("Ram:") => DeviceKind.Ram(SyncRamPorts.parseOrThrow(s.stripPrefix("Ram:")))
                    case other => throw new IllegalArgumentException(s"unknown device kind '$other'")
                }
            )
