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
    PeripheralBridgeParameter,
    RamFabricSlave,
    RamFabricSlaveParameter,
    RamPins,
    SifiveTestFinisher,
    SifiveTestFinisherParameter,
    UartParameter,
    UartPins
}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.*
import nzea_rtl.bus.given
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.SyncRamPorts
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class DeviceContext(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    base: Long,
    size: Long,
    clockHz: Int,
    timeHz: Int,
    stats: StatScope
)

/** The kinds of device the library ships: the one place that knows what a device is on the address map and how to build
  * it. A new device is its own module plus one case here. The port shape a kind carries is the target macro's
  * ([[SyncRamPorts]]), stated by the caller's device list rather than baked into the device, so a different macro is a
  * different entry.
  */
enum DeviceKind:
    case Ram(ports: SyncRamPorts)
    case Uart(lineHz: Int)
    case Finisher
    case Clint
    case PeripheralBridge

    def slaveKind: SlaveKind = this match
        case Ram(_) => SlaveKind.Memory
        case _      => SlaveKind.Mmio

    /** The pins this kind shows at the boundary for a flow; `None` when it shows none — a device whose hardware is not
      * ported, or the bridge flow, where the simulator answers it through DPI. Storage shows the clock its memory runs
      * on: the macro's cycle time is its own, so it is not the tile's clock.
      */
    def pins(flow: RtlFlow)(using TypeImpl, ConstructorApi): Option[Bundle] = this match
        case Ram(_)           => if flow.bridges then None else Some(new RamPins)
        case Uart(_)          => if flow.bridges then None else Some(new UartPins)
        case Finisher         => if flow.bridges then None else Some(new FinisherPins)
        case Clint            => if flow.bridges then None else Some(new ClintPins)
        case PeripheralBridge => None

    /** Whether the boundary is the level's input (a clock the level supplies) or its output (a flag the device shows).
      * Inputs flow down — `level :>= device.boundary` — outputs flow up.
      */
    def boundaryIsInput: Boolean = this match
        case Ram(_) => true
        case _      => false

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
                    ctx.clockHz,
                    ctx.base,
                    ctx.size,
                    ports,
                    flow
                )
            )
        case Uart(lineHz) =>
            // Fully qualified: inside the enum, `Uart` is the case, and the case inherits this very method.
            nzea_device.devices.Uart.instantiate(
                UartParameter(
                    ctx.addrWidth,
                    ctx.dataWidth,
                    ctx.userWidth,
                    ctx.idWidth,
                    ctx.base,
                    ctx.clockHz,
                    lineHz,
                    flow
                )
            )
        case Finisher =>
            SifiveTestFinisher.instantiate(
                SifiveTestFinisherParameter(
                    ctx.addrWidth,
                    ctx.dataWidth,
                    ctx.userWidth,
                    ctx.idWidth,
                    ctx.clockHz,
                    flow
                )
            )
        case Clint =>
            // Fully qualified for the same reason as `Uart`.
            nzea_device.devices.Clint.instantiate(
                ClintParameter(
                    ctx.addrWidth,
                    ctx.dataWidth,
                    ctx.userWidth,
                    ctx.idWidth,
                    ctx.base,
                    ctx.clockHz,
                    ctx.timeHz,
                    flow
                )
            )
        case PeripheralBridge =>
            // Fully qualified for the same reason as `Uart`.
            nzea_device.devices.PeripheralBridge.instantiate(
                PeripheralBridgeParameter(
                    ctx.addrWidth,
                    ctx.dataWidth,
                    ctx.userWidth,
                    ctx.idWidth,
                    ctx.clockHz,
                    DeviceFrames.frames,
                    flow,
                    ctx.stats
                )
            )

    /** Drive the boundary of a device the level does not expose.
      *
      * A boundary field may be an *input* (the RAM's memory clock), and an undriven input is an error in the emitted
      * RTL. The bridge flow exposes no boundary and builds no hardware behind it, so the level's own clock is a fine
      * stand-in — the port exists only so the device's interface is well-formed.
      */
    def tieUnexposedBoundary(device: Instance[?, ?], clock: Referable[Clock])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit = this match
        case Ram(_) =>
            val stub = Wire(new RamPins)
            stub.asRecord.field[Clock]("clock") := clock
            stub :>= device.io.field[RamPins]("boundary")
        case _ => ()

object DeviceKind:

    /** One token per kind, `Ram:<shape>` for storage: the CLI flag's spelling and the config file's, one spelling for a
      * kind across both.
      */
    given upickle.default.ReadWriter[DeviceKind] =
        upickle.default
            .readwriter[String]
            .bimap(
                {
                    case DeviceKind.Ram(ports)       => s"Ram:${ports.name}"
                    case DeviceKind.Uart(lineHz)     => s"Uart:$lineHz"
                    case DeviceKind.Finisher         => "Finisher"
                    case DeviceKind.Clint            => "Clint"
                    case DeviceKind.PeripheralBridge => "PeripheralBridge"
                },
                {
                    case "Finisher"                 => DeviceKind.Finisher
                    case "Clint"                    => DeviceKind.Clint
                    case "PeripheralBridge"         => DeviceKind.PeripheralBridge
                    case s if s.startsWith("Ram:")  => DeviceKind.Ram(SyncRamPorts.parseOrThrow(s.stripPrefix("Ram:")))
                    case s if s.startsWith("Uart:") => DeviceKind.Uart(s.stripPrefix("Uart:").toInt)
                    case other => throw new IllegalArgumentException(s"unknown device kind '$other'")
                }
            )
