package nzea_rtl.bus

import java.lang.foreign.Arena

import mainargs.{ParserForClass, TokensReader}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A device's bus timing in the simulator: a fixed access latency and the jitter around the handshakes.
  *
  * `latency` counts from the request's acceptance to the response's offer. The delay is a line, not a residency:
  * back-to-back requests stay one per cycle, so a latency does not cap throughput.
  *
  * The jitter is part of the model, not an option: the handshakes are held at random — a draw lands a hold with
  * probability `1 / jitterEvery`, delaying the handshake by `0..jitterSpan` cycles (uniform over the range). The
  * request side holds `ready` and redraws while the master waits; the response side holds `valid` (in order). A master
  * therefore sees random handshakes — the shape that exercises its hold and retry paths. The random source is a
  * fixed-seed MCG, so a run is reproducible.
  */
case class DeviceTiming(latency: Int, jitterEvery: Int, jitterSpan: Int):
    require(latency >= 2, s"latency must be >= 2, got $latency")
    require(jitterEvery >= 1 && jitterEvery <= 256, s"jitterEvery must be in [1, 256], got $jitterEvery")
    require(jitterSpan >= 1, s"jitterSpan must be >= 1, got $jitterSpan")

object DeviceTiming:
    given upickle.default.ReadWriter[DeviceTiming] = upickle.default.macroRW

    /** The generator framework needs a reader for every parameter field; this is not a user-facing knob — the devices'
      * timings live in `nzea_device.DeviceTimings` and only the bridge flow reads them.
      */
    given TokensReader[DeviceTiming] = ParserForClass[DeviceTiming]

case class LiteBusTimingParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int, timing: DeviceTiming)
    extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

given upickle.default.ReadWriter[LiteBusTimingParameter] = upickle.default.macroRW

class LiteBusTimingLayers(parameter: LiteBusTimingParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class LiteBusTimingIO(parameter: LiteBusTimingParameter) extends HWBundle(parameter):
    private val bus = new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth)

    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The fabric side: the module is its slave. */
    val in = Flipped(bus)

    /** The device side: the module is its master. */
    val out = Aligned(bus)

class LiteBusTimingProbe(parameter: LiteBusTimingParameter)
    extends DVBundle[LiteBusTimingParameter, LiteBusTimingLayers](parameter)

@generator
object LiteBusTiming
    extends Generator[LiteBusTimingParameter, LiteBusTimingLayers, LiteBusTimingIO, LiteBusTimingProbe]:

    /** The module is named by its whole interface — the timing is the body — so one name is one body: several devices
      * with different timings must not share a module.
      */
    override def moduleName(parameter: LiteBusTimingParameter): String =
        val t = parameter.timing
        s"LiteBusTiming_latency${t.latency}_jitterEvery${t.jitterEvery}_jitterSpan${t.jitterSpan}"

    def architecture(parameter: LiteBusTimingParameter) =
        val io = summon[Interface[LiteBusTimingIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val t = parameter.timing
        val holdW = log2Ceil(t.jitterSpan + 1)

        // The random source: an MCG (5x+1, full period) xor a free counter — deterministic from reset, so a run is
        // reproducible. The two directions read different bit fields, so their draws are independent.
        val mcga = RegInit(1.U(16))
        val ctr = RegInit(0.U(16))
        mcga := addWrap(addWrap(mcga << 2, mcga, 16), 1.U(16), 16)
        ctr := addWrap(ctr, 1.U(16), 16)
        val randBits: Node[Bits] = Node(mcga.asBits)
        val swapped: Node[Bits] = Node(randBits.bits(7, 0) ## randBits.bits(15, 8))
        val randReq: Node[UInt] = (swapped ^ ctr.asBits).asUInt
        val randResp: Node[UInt] = (randBits ^ ctr.asBits).asUInt

        // One event in `jitterEvery` on average (the high byte against a threshold), its magnitude from the low bits
        // clamped to the span.
        val eventThresh = math.min(math.max(256 / t.jitterEvery, 1), 255)

        def draw(r: Referable[UInt]): Node[UInt] =
            val bits: Node[Bits] = Node(r.asBits)
            val event: Referable[Bool] = bits.bits(15, 8).asUInt < eventThresh.U(8)
            val magnitude: Node[UInt] = bits.bits(holdW - 1, 0).asUInt
            val clamped: Node[UInt] =
                (magnitude > t.jitterSpan.U(holdW)) ? (t.jitterSpan.U(holdW), magnitude)
            event ? (clamped, 0.U(holdW))

        // ---- Request side: the jitter holds `ready`; the request itself passes through ----
        val reqHold = RegInit(0.U(holdW))
        val reqIdle = reqHold === 0.U(holdW)
        val reqDraw = draw(randReq)
        val reqCandidate = reqIdle & io.in.req.valid & io.out.req.ready
        // A nonzero draw makes this cycle a hold: neither side fires, and the request is not presented to the device
        // until the hold runs out — one master handshake is exactly one device handshake. Presenting it while holding
        // would let the device accept the same request on every hold cycle, one spurious execution and response each.
        val reqStall = reqCandidate & (reqDraw =/= 0.U(holdW))
        io.out.req.valid := io.in.req.valid & reqIdle & !reqStall
        io.out.req.bits :<= io.in.req.bits
        io.in.req.ready := io.out.req.ready & reqIdle & !reqStall
        when(reqStall) {
            reqHold := reqDraw
        }.otherwise {
            when(!reqIdle) { reqHold := subWrap(reqHold, 1.U(holdW), holdW) }
        }

        // ---- Response side: the latency line, then the jitter's hold ----
        // `latency - 2`: the device's own answer takes one cycle, and the output stage below takes another.
        val stages = t.latency - 2
        val stageValid = Seq.tabulate(stages)(_ => RegInit(false.B))
        val stageData = Seq.tabulate(stages)(_ => Reg(UInt(parameter.dataWidth)))
        val stageUser = Seq.tabulate(stages)(_ => Reg(UInt(parameter.userWidth)))
        val stageId = Seq.tabulate(stages)(_ => Reg(UInt(parameter.idWidth)))

        val outValid = RegInit(false.B)
        val outData = Reg(UInt(parameter.dataWidth))
        val outUser = Reg(UInt(parameter.userWidth))
        val outId = Reg(UInt(parameter.idWidth))
        val respHold = RegInit(0.U(holdW))
        val respDue = respHold === 0.U(holdW)
        // Drawn here, not inside the `when` below: a helper's ops land in the block its definition sees, and one created
        // inside the block would not dominate its own use.
        val respDraw = draw(randResp)

        val lastValid: Referable[Bool] = if stages == 0 then io.out.resp.valid else stageValid(stages - 1)
        val lastData: Referable[UInt] = if stages == 0 then io.out.resp.bits.data else stageData(stages - 1)
        val lastUser: Referable[UInt] = if stages == 0 then io.out.resp.bits.user else stageUser(stages - 1)
        val lastId: Referable[UInt] = if stages == 0 then io.out.resp.bits.id else stageId(stages - 1)

        // The line and the output stage move together: a taken output makes room for the line to shift, and a held one
        // stops it — in order, which is what a device's responses are.
        val advance = !outValid | (outValid & respDue & io.in.resp.ready)
        io.out.resp.ready := advance
        val rawFire = io.out.resp.valid & io.out.resp.ready

        io.in.resp.valid := outValid & respDue
        io.in.resp.bits.data := outData
        io.in.resp.bits.user := outUser
        io.in.resp.bits.id := outId

        when(advance) {
            when(lastValid) {
                outValid := true.B
                outData := lastData
                outUser := lastUser
                outId := lastId
                respHold := respDraw
            }.otherwise {
                outValid := false.B
            }
            if stages > 0 then
                for i <- 1 until stages do
                    stageValid(i) := stageValid(i - 1)
                    stageData(i) := stageData(i - 1)
                    stageUser(i) := stageUser(i - 1)
                    stageId(i) := stageId(i - 1)
                stageValid(0) := rawFire
                when(rawFire) {
                    stageData(0) := io.out.resp.bits.data
                    stageUser(0) := io.out.resp.bits.user
                    stageId(0) := io.out.resp.bits.id
                }
        }.otherwise {
            when(outValid & !respDue) { respHold := subWrap(respHold, 1.U(holdW), holdW) }
        }
