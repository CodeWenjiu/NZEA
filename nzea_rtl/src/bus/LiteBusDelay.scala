package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class LiteBusDelayParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    latency: Int
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(latency >= 1, s"latency must be >= 1, got $latency")

given upickle.default.ReadWriter[LiteBusDelayParameter] = upickle.default.macroRW

class LiteBusDelayLayers(parameter: LiteBusDelayParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class LiteBusDelayIO(parameter: LiteBusDelayParameter) extends HWBundle(parameter):
    private val bus = new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth)

    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The fabric side: the module is its slave. */
    val in = Flipped(bus)

    /** The device side: the module is its master. */
    val out = Aligned(bus)

class LiteBusDelayProbe(parameter: LiteBusDelayParameter)
    extends DVBundle[LiteBusDelayParameter, LiteBusDelayLayers](parameter)

/** One port's own delay: the device's answer arrives `latency` bus cycles after the request handshake, and answers keep
  * their order. Only the answer path is shifted — a request reaches the device as it arrives, so a port with a long
  * latency still takes one request per cycle while its answers are in flight.
  */
@generator
object LiteBusDelay extends Generator[LiteBusDelayParameter, LiteBusDelayLayers, LiteBusDelayIO, LiteBusDelayProbe]:

    override def moduleName(parameter: LiteBusDelayParameter): String =
        s"LiteBusDelay_latency${parameter.latency}"

    def architecture(parameter: LiteBusDelayParameter) =
        val io = summon[Interface[LiteBusDelayIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        io.out.req.valid := io.in.req.valid
        io.out.req.bits :<= io.in.req.bits
        io.in.req.ready := io.out.req.ready

        // The first cycle of the delay is the device's own answer register; `stages` more make the rest.
        val stages = parameter.latency - 1
        val stageValid = Seq.tabulate(stages)(_ => RegInit(false.B))
        val stageData = Seq.tabulate(stages)(_ => Reg(UInt(parameter.dataWidth)))
        val stageUser = Seq.tabulate(stages)(_ => Reg(UInt(parameter.userWidth)))
        val stageId = Seq.tabulate(stages)(_ => Reg(UInt(parameter.idWidth)))

        val outValid: Referable[Bool] = if stages == 0 then io.out.resp.valid else stageValid(stages - 1)
        val outData: Referable[UInt] = if stages == 0 then io.out.resp.bits.data else stageData(stages - 1)
        val outUser: Referable[UInt] = if stages == 0 then io.out.resp.bits.user else stageUser(stages - 1)
        val outId: Referable[UInt] = if stages == 0 then io.out.resp.bits.id else stageId(stages - 1)

        // The line and the master move together: a taken answer makes room for the next, a held one stops the device.
        val advance = !outValid | io.in.resp.ready
        io.out.resp.ready := advance
        val take = io.out.resp.valid & advance

        io.in.resp.valid := outValid
        io.in.resp.bits.data := outData
        io.in.resp.bits.user := outUser
        io.in.resp.bits.id := outId

        when(advance) {
            if stages > 0 then
                for i <- 1 until stages do
                    stageValid(i) := stageValid(i - 1)
                    stageData(i) := stageData(i - 1)
                    stageUser(i) := stageUser(i - 1)
                    stageId(i) := stageId(i - 1)
                stageValid(0) := take
                when(take) {
                    stageData(0) := io.out.resp.bits.data
                    stageUser(0) := io.out.resp.bits.user
                    stageId(0) := io.out.resp.bits.id
                }
        }
