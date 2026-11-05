package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.{HasBridgeDelay, HasDpiBus}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{Delay, LiteBusRW}
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
    flow: RtlFlow
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
  * the bridge costs no re-elaboration — and the window's own pace ([[PeripheralBridge.bridgeDelay]]) is what a master
  * sees: the display group's window is plain memory, one access at the secondary bus's own pace. A synthesis flow
  * builds that secondary bus (`PeripheralBus`) with the same two rate facts, so both flows answer in the bus's time.
  */
@generator
object PeripheralBridge
    extends Generator[PeripheralBridgeParameter, PeripheralBridgeLayers, PeripheralBridgeIO, PeripheralBridgeProbe]
    with HasDpiBus
    with HasBridgeDelay[PeripheralBridgeParameter]:
    override def moduleName(parameter: PeripheralBridgeParameter): String = "PeripheralBridge"

    /** The window's delay is its own pace: one access takes `deviceCycles` of the device clock. */
    override def bridgeDelay(parameter: PeripheralBridgeParameter): Option[Delay] =
        Some(Delay(hz = parameter.deviceHz, cycles = parameter.deviceCycles))

    def architecture(parameter: PeripheralBridgeParameter) =
        val io = summon[Interface[PeripheralBridgeIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        answerBus(
            parameter.flow,
            bridgeDelay(parameter),
            parameter.clockHz,
            io.bus,
            io.resetN.asBool,
            parameter.addrWidth,
            parameter.dataWidth,
            parameter.userWidth,
            parameter.idWidth
        ):
            // The window with real hardware behind it: the secondary bus paces an access by its own divided clock, so a
            // master sees the wire's time in the synthesis flow too — the same arithmetic the bridge flow's delay
            // resolves.
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
