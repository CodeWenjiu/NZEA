package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.{DeviceTimings, HasDpiBus}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{
    AddrRange,
    DeviceTiming,
    FrameTiming,
    FramedRange,
    LiteBusRW,
    LiteBusResidency,
    LiteBusResidencyParameter
}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class PeripheralBridgeParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    clockHz: Int,
    deviceHz: Int,
    deviceCycles: Int,
    frames: Seq[FramedRange],
    flow: RtlFlow,
    stats: StatScope
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(clockHz >= 1, s"clockHz must be >= 1, got $clockHz")
    require(deviceHz >= 1, s"deviceHz must be >= 1, got $deviceHz")
    require(clockHz % deviceHz == 0, s"the device clock must divide the bus clock: $deviceHz Hz against $clockHz Hz")
    require(deviceCycles >= 1, s"deviceCycles must be >= 1, got $deviceCycles")

given upickle.default.ReadWriter[PeripheralBridgeParameter] = upickle.default.macroRW

class PeripheralBridgeLayers(parameter: PeripheralBridgeParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class PeripheralBridgeIO(parameter: PeripheralBridgeParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class PeripheralBridgeProbe(parameter: PeripheralBridgeParameter)
    extends DVBundle[PeripheralBridgeParameter, PeripheralBridgeLayers](parameter)

/** One window in front of a secondary bus: the fabric decodes the window and the bridge fans out inside it, which is
  * how a group of peripherals stays off the main interconnect's arbitration.
  *
  * The bridge flow has the simulator behind the window — the answer routes by address there, so a device added behind
  * the bridge costs no re-elaboration. A synthesis flow has no peripherals behind it yet: the bridge hands the window
  * to [[Todo]], whose access panics in simulation while the handshake keeps synthesis legal.
  */
@generator
object PeripheralBridge
    extends Generator[PeripheralBridgeParameter, PeripheralBridgeLayers, PeripheralBridgeIO, PeripheralBridgeProbe]
    with HasDpiBus:
    override def moduleName(parameter: PeripheralBridgeParameter): String = "PeripheralBridge"

    override def timing: DeviceTiming = DeviceTimings.peripheral

    def architecture(parameter: PeripheralBridgeParameter) =
        val io = summon[Interface[PeripheralBridgeIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        if parameter.flow.bridges then
            // The window's answer with the wire's residence in front of it: a hit range's access is one frame. The
            // bridge's own forward — the four-cycle line and the simulator behind it — stays where it was.
            val inner = Wire(
                new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth)
            )
            bridgeAnswer(
                parameter.clockHz,
                inner,
                io.resetN.asBool,
                parameter.addrWidth,
                parameter.dataWidth,
                parameter.userWidth,
                parameter.idWidth
            )
            val residency = LiteBusResidency.instantiate(
                LiteBusResidencyParameter(
                    parameter.addrWidth,
                    parameter.dataWidth,
                    parameter.userWidth,
                    parameter.idWidth,
                    parameter.clockHz,
                    parameter.frames,
                    parameter.flow.counters,
                    parameter.stats.child(LiteBusResidency.Source)
                )
            )
            residency.io.clock := summon[ClockScope].clock
            residency.io.resetN := io.resetN.asBool.asReset

            residency.io.in.req.valid := io.bus.req.valid
            residency.io.in.req.bits :<= io.bus.req.bits
            io.bus.req.ready := residency.io.in.req.ready
            io.bus.resp.valid := residency.io.in.resp.valid
            io.bus.resp.bits :<= residency.io.in.resp.bits
            residency.io.in.resp.ready := io.bus.resp.ready

            inner.req.valid := residency.io.out.req.valid
            inner.req.bits :<= residency.io.out.req.bits
            residency.io.out.req.ready := inner.req.ready
            residency.io.out.resp.valid := inner.resp.valid
            residency.io.out.resp.bits :<= inner.resp.bits
            inner.resp.ready := residency.io.out.resp.ready
        else
            // The window with real hardware behind it: the secondary bus paces an access by its own divided clock, so a
            // master sees the wire's time in the synthesis flow too — the same arithmetic the bridge flow's model
            // resolves above.
            val bus = PeripheralBus.instantiate(
                PeripheralBusParameter(
                    parameter.addrWidth,
                    parameter.dataWidth,
                    parameter.userWidth,
                    parameter.idWidth,
                    parameter.clockHz,
                    parameter.deviceHz,
                    parameter.deviceCycles
                )
            )
            bus.io.clock := summon[ClockScope].clock
            bus.io.resetN := io.resetN
            bus.io.bus.req.valid := io.bus.req.valid
            bus.io.bus.req.bits :<= io.bus.req.bits
            io.bus.req.ready := bus.io.bus.req.ready
            io.bus.resp.valid := bus.io.bus.resp.valid
            io.bus.resp.bits :<= bus.io.bus.resp.bits
            bus.io.bus.resp.ready := io.bus.resp.ready
