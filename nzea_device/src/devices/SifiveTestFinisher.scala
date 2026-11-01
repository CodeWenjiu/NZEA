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

/** A SiFive test finisher: a write in its window raises [[SifiveTestFinisherIO.finished]], which ends a simulation run;
  * reads answer zero. The port is one register deep — the answer is due the cycle after the request, with no state of
  * its own — and the request's `user`/`id` are captured at acceptance because the master has moved on by the time the
  * answer is due. In the bridge flow the simulator ends the run from the write it sees, so [[HasDpiBus.answerBus]]
  * answers the bus and `finished` is inert.
  */
case class SifiveTestFinisherParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    clockHz: Int,
    flow: RtlFlow
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(clockHz >= 1, s"clockHz must be >= 1, got $clockHz")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

given upickle.default.ReadWriter[SifiveTestFinisherParameter] = upickle.default.macroRW

class SifiveTestFinisherLayers(parameter: SifiveTestFinisherParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class FinisherPins(using TypeImpl, ConstructorApi) extends Bundle:
    val finished = Aligned(Bool())

class SifiveTestFinisherIO(parameter: SifiveTestFinisherParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
    val boundary = Aligned(new FinisherPins)

class SifiveTestFinisherProbe(parameter: SifiveTestFinisherParameter)
    extends DVBundle[SifiveTestFinisherParameter, SifiveTestFinisherLayers](parameter)

@generator
object SifiveTestFinisher
    extends Generator[
        SifiveTestFinisherParameter,
        SifiveTestFinisherLayers,
        SifiveTestFinisherIO,
        SifiveTestFinisherProbe
    ]
    with HasDpiBus:
    override def moduleName(parameter: SifiveTestFinisherParameter): String = "SifiveTestFinisher"

    def architecture(parameter: SifiveTestFinisherParameter) =
        val io = summon[Interface[SifiveTestFinisherIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        answerBus(
            parameter.flow,
            None,
            parameter.clockHz,
            io.bus,
            io.resetN.asBool,
            parameter.addrWidth,
            parameter.dataWidth,
            parameter.userWidth,
            parameter.idWidth
        ):
            val finished = RegInit(false.B)
            val busy = RegInit(false.B)
            val respUser = RegInit(0.U(parameter.userWidth))
            val respId = RegInit(0.U(parameter.idWidth))

            io.bus.req.ready := !busy
            io.bus.resp.valid := busy
            io.bus.resp.bits.data := 0.U(parameter.dataWidth)
            io.bus.resp.bits.user := respUser
            io.bus.resp.bits.id := respId
            io.boundary.finished := finished

            val reqFire = io.bus.req.valid & io.bus.req.ready
            when(reqFire) {
                respUser := io.bus.req.bits.user
                respId := io.bus.req.bits.id
                busy := true.B
                when(io.bus.req.bits.wen) { finished := true.B }
            }
            when(io.bus.resp.valid & io.bus.resp.ready) { busy := false.B }

        // The hardware-only output: inert when the simulator answers the bus instead.
        if parameter.flow.bridges then io.boundary.finished := false.B
