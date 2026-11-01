package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class LiteBusTimingParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    busHz: Int,
    timing: BusTiming,
    counters: Boolean,
    stats: StatScope
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(busHz >= 1, s"busHz must be >= 1, got $busHz")

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

/** One port's timing, driven by its [[BusTiming]] table: the answer arrives after the access's cost, and a row that
  * holds the port keeps the next access out for that whole cost.
  *
  * The two behaviours live together because they share one port. The line is the port's own cost — one uniform delay, a
  * jittery handshake, and answers that stay in order; the residence is what a `Held` row adds — the request is
  * forwarded as the wire takes it, the answer is captured, and the port stays busy until the row's own cost has run
  * out, so the frame caps throughput as well as latency. A table with no `Held` row is exactly the line, which is what
  * a device on a port of its own is.
  *
  * A hit request waits for the port to fall quiet before it starts: the answer captured for a frame must be the frame's
  * own, and the pipelined path behind can still be carrying an earlier access's answer otherwise.
  */
@generator
object LiteBusTiming
    extends Generator[LiteBusTimingParameter, LiteBusTimingLayers, LiteBusTimingIO, LiteBusTimingProbe]:

    /** The block name the residence's counters are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "residency"

    /** The module is named by its whole timing — the latency, the jitter, and every held row — so one name is one body.
      */
    override def moduleName(parameter: LiteBusTimingParameter): String =
        val t = parameter.timing
        val latency = t.line.cost.at(parameter.busHz)
        val held = t.held
            .map(entry =>
                val range = entry.range.get
                s"${range.base.toString(16)}x${range.size.toString(16)}c${entry.cost.at(parameter.busHz)}"
            )
            .mkString("_")
        val frames = if held.isEmpty then "" else s"_$held"
        s"LiteBusTiming_latency${latency}_jitterEvery${t.jitterEvery}_jitterSpan${t.jitterSpan}$frames"

    def architecture(parameter: LiteBusTimingParameter) =
        val io = summon[Interface[LiteBusTimingIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val t = parameter.timing
        val latency = t.line.cost.at(parameter.busHz)
        val held = t.held

        // The residence's device side is the line's fabric side: one port, two behaviours.
        val inner = Wire(
            new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth)
        )

        // ---- the line: `inner` in, `io.out` out -------------------------------------------------------------
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

        val eventThresh = math.min(math.max(256 / t.jitterEvery, 1), 255)

        def draw(r: Referable[UInt]): Node[UInt] =
            val bits: Node[Bits] = Node(r.asBits)
            val event: Referable[Bool] = bits.bits(15, 8).asUInt < eventThresh.U(8)
            val magnitude: Node[UInt] = bits.bits(holdW - 1, 0).asUInt
            val clamped: Node[UInt] = (magnitude > t.jitterSpan.U(holdW)) ? (t.jitterSpan.U(holdW), magnitude)
            event ? (clamped, 0.U(holdW))

        // Request side: the jitter holds `ready`; the request itself passes through.
        val reqHold = RegInit(0.U(holdW))
        val reqIdle = reqHold === 0.U(holdW)
        val reqDraw = draw(randReq)
        val reqCandidate = reqIdle & inner.req.valid & io.out.req.ready
        // A nonzero draw makes this cycle a hold: neither side fires, and the request is not presented to the device
        // until the hold runs out — one master handshake is exactly one device handshake. Presenting it while holding
        // would let the device accept the same request on every hold cycle, one spurious execution and response each.
        val reqStall = reqCandidate & (reqDraw =/= 0.U(holdW))
        io.out.req.valid := inner.req.valid & reqIdle & !reqStall
        io.out.req.bits :<= inner.req.bits
        inner.req.ready := io.out.req.ready & reqIdle & !reqStall
        when(reqStall) {
            reqHold := reqDraw
        }.otherwise {
            when(!reqIdle) { reqHold := subWrap(reqHold, 1.U(holdW), holdW) }
        }

        // Response side: the declared latency counts registers from the request handshake, and the simulator's answer
        // is the first of them — `stages` more make the rest.
        val stages = latency - 1
        val stageValid = Seq.tabulate(stages)(_ => RegInit(false.B))
        val stageData = Seq.tabulate(stages)(_ => Reg(UInt(parameter.dataWidth)))
        val stageUser = Seq.tabulate(stages)(_ => Reg(UInt(parameter.userWidth)))
        val stageId = Seq.tabulate(stages)(_ => Reg(UInt(parameter.idWidth)))

        val respHold = RegInit(0.U(holdW))
        val respDue = respHold === 0.U(holdW)
        // Drawn here, not inside the `when` below: a helper's ops land in the block its definition sees, and one created
        // inside the block would not dominate its own use.
        val respDraw = draw(randResp)

        // The master-facing stage: ours when the table declares one, otherwise the simulator's own answer register.
        val outValid: Referable[Bool] = if stages == 0 then io.out.resp.valid else stageValid(stages - 1)
        val outData: Referable[UInt] = if stages == 0 then io.out.resp.bits.data else stageData(stages - 1)
        val outUser: Referable[UInt] = if stages == 0 then io.out.resp.bits.user else stageUser(stages - 1)
        val outId: Referable[UInt] = if stages == 0 then io.out.resp.bits.id else stageId(stages - 1)

        // The line and the master move together: a taken answer makes room for the next, and a held one stops the line —
        // in order, which is what a device's responses are.
        val advance = !outValid | (outValid & respDue & inner.resp.ready)
        io.out.resp.ready := advance
        val rawFire = io.out.resp.valid & io.out.resp.ready

        inner.resp.valid := outValid & respDue
        inner.resp.bits.data := outData
        inner.resp.bits.user := outUser
        inner.resp.bits.id := outId

        when(advance) {
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
        }
        // An answer handed over draws the next one's hold; a waiting one counts its hold down.
        when(advance & outValid & respDue) {
            respHold := respDraw
        }.otherwise {
            when(outValid & !respDue) { respHold := subWrap(respHold, 1.U(holdW), holdW) }
        }

        // ---- the residence: `io.in` in, `inner` out ---------------------------------------------------------
        if held.isEmpty then
            // One row, one line: the port does not hold, so the fabric sees the line directly.
            inner.req.valid := io.in.req.valid
            inner.req.bits :<= io.in.req.bits
            io.in.req.ready := inner.req.ready
            io.in.resp.valid := inner.resp.valid
            io.in.resp.bits :<= inner.resp.bits
            inner.resp.ready := io.in.resp.ready
        else
            val entries: Seq[(AddrRange, Int)] = held.map(entry => (entry.range.get, entry.cost.at(parameter.busHz)))
            val cyclesW = log2Ceil(entries.map(pair => pair._2).max + 1)
            // The port's pipeline is a few entries deep at most; a byte leaves room to spare.
            val outstandingW = 8

            // The hit and the frame's length, combinational over the table.
            val addr: Referable[UInt] = io.in.req.bits.addr
            def inRange(range: AddrRange): Referable[Bool] =
                val base = range.base.U(parameter.addrWidth)
                val top = (range.base + range.size).U(parameter.addrWidth)
                (addr >= base) & (addr < top)

            val hit: Referable[Bool] = entries.map((range, _) => inRange(range)).reduce(_ | _)
            val frameCycles: Referable[UInt] = entries.foldRight[Referable[UInt]](0.U(cyclesW)) {
                case ((range, cycles), acc) => inRange(range) ? (cycles.U(cyclesW), acc)
            }

            // A frame in flight: `busy` holds through its whole length, `held` keeps the answer it captured.
            val busy = RegInit(false.B)
            val remain = RegInit(0.U(cyclesW))
            val heldValid = RegInit(false.B)
            val heldData = Reg(UInt(parameter.dataWidth))
            val heldUser = Reg(UInt(parameter.userWidth))
            val heldId = Reg(UInt(parameter.idWidth))

            // Requests taken off the port and not yet answered: a hit starts only when this is zero, so the answer it
            // captures is its own.
            val outstanding = RegInit(0.U(outstandingW))
            val quiet = outstanding === 0.U(outstandingW)

            // A hit request waits for quiet: neither side fires, and the request stays upstream until then.
            val waitQuiet = io.in.req.valid & hit & !quiet
            val idle = !busy & !heldValid
            io.in.req.ready := inner.req.ready & idle & !waitQuiet
            inner.req.valid := io.in.req.valid & idle & !waitQuiet
            inner.req.bits :<= io.in.req.bits

            val fire = io.in.req.valid & io.in.req.ready
            val started = fire & hit

            // The request side counts what is outstanding; the answer side takes one back.
            val outFire = inner.req.valid & inner.req.ready
            val respFire = inner.resp.valid & inner.resp.ready
            when(outFire & !respFire) {
                outstanding := addWrap(outstanding, 1.U(outstandingW), outstandingW)
            }.otherwise {
                when(!outFire & respFire) {
                    outstanding := subWrap(outstanding, 1.U(outstandingW), outstandingW)
                }
            }

            // While a frame is in flight the answer side captures; otherwise it is the upstream's, straight through.
            val capture = busy & inner.resp.valid & !heldValid
            inner.resp.ready := busy ? (!heldValid, io.in.resp.ready)
            when(capture) {
                heldValid := true.B
                heldData := inner.resp.bits.data
                heldUser := inner.resp.bits.user
                heldId := inner.resp.bits.id
            }

            // The frame's length runs out: the captured answer becomes visible, and the port frees when it is taken.
            val release = busy & (remain === 0.U(cyclesW))
            io.in.resp.valid := busy ? ((heldValid & release), inner.resp.valid)
            io.in.resp.bits.data := busy ? (heldData, inner.resp.bits.data)
            io.in.resp.bits.user := busy ? (heldUser, inner.resp.bits.user)
            io.in.resp.bits.id := busy ? (heldId, inner.resp.bits.id)

            when(started) {
                busy := true.B
                remain := subWrap(frameCycles, 1.U(cyclesW), cyclesW)
            }.otherwise {
                when(busy) {
                    when(remain === 0.U(cyclesW)) {
                        when(heldValid & io.in.resp.ready) {
                            busy := false.B
                            heldValid := false.B
                        }
                    }.otherwise {
                        remain := subWrap(remain, 1.U(cyclesW), cyclesW)
                    }
                }
            }

            if parameter.counters then
                val stats = parameter.stats.child(Source)
                val frames = stats.stat("frames", BusRegions.Bus, "frames started (one access each)")
                frames.inc(started)
                val busyCycles = stats.stat("busy", BusRegions.Bus, "cycles the port is held by a frame")
                busyCycles.inc(busy)
