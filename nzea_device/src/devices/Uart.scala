package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.HasDpiBus
import nzea_rtl.RtlFlow
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The platform's UART, in its window at `base`.
  *
  * Only the bridge flow's answer exists so far: [[HasDpiBus.answerBus]] forwards the bus to the simulator, which is
  * what a run needs. The hardware — a 16550-compatible register file, a baud generator and the serial pins — is not
  * ported yet; the ports flow that would need it is where it lands.
  */
case class UartParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int, base: Long, flow: RtlFlow)
    extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(base >= 0, s"base must be >= 0, got $base")

given upickle.default.ReadWriter[UartParameter] = upickle.default.macroRW

class UartLayers(parameter: UartParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class UartIO(parameter: UartParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class UartProbe(parameter: UartParameter) extends DVBundle[UartParameter, UartLayers](parameter)

@generator
object Uart extends Generator[UartParameter, UartLayers, UartIO, UartProbe] with HasDpiBus:
    override def moduleName(parameter: UartParameter): String = "Uart"

    def architecture(parameter: UartParameter) =
        val io = summon[Interface[UartIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        answerBus(
            parameter.flow,
            io.bus,
            io.resetN.asBool,
            parameter.dataWidth,
            parameter.userWidth,
            parameter.idWidth
        ):
            // TODO: the UART's hardware is not ported; only the bridge flow's answer exists.
            require(false, "the UART has no hardware yet — only its bridge-flow answer is ported")
