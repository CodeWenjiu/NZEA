package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.{DeviceTimings, HasDpiBus}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{DeviceTiming, LiteBusRW}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class PeripheralBridgeParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int, flow: RtlFlow)
    extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

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

        answerBus(
            parameter.flow,
            io.bus,
            io.resetN.asBool,
            parameter.addrWidth,
            parameter.dataWidth,
            parameter.userWidth,
            parameter.idWidth
        ):
            Todo.answer(
                io.bus,
                io.resetN.asBool,
                parameter.dataWidth,
                parameter.userWidth,
                parameter.idWidth
            )
