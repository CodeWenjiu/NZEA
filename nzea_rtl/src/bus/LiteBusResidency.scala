package nzea_rtl.bus

import java.lang.foreign.Arena

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A wire's own facts: the rate it shifts bits at, and the bits one frame takes — the total the frame occupies the wire
  * for, framing overhead included, not the data bits alone. The cycle cost is derived against the platform clock
  * ([[cycles]]), the way the UART's divisor is its line rate: a fact of the wire, not a tunable.
  */
case class FrameTiming(bitHz: Int, bitsPerFrame: Int):
    require(bitHz >= 1, s"bitHz must be >= 1, got $bitHz")
    require(bitsPerFrame >= 1, s"bitsPerFrame must be >= 1, got $bitsPerFrame")

    /** The whole frame in platform cycles, rounded up: a frame ends inside a cycle, never between two. */
    def cycles(clockHz: Int): Int = ((BigInt(bitsPerFrame) * clockHz + bitHz - 1) / bitHz).toInt

object FrameTiming:
    given upickle.default.ReadWriter[FrameTiming] = upickle.default.macroRW

/** One entry of the residency's table: the address range a wire serves, and that wire's frame. */
case class FramedRange(range: AddrRange, timing: FrameTiming)

object FramedRange:
    given upickle.default.ReadWriter[FramedRange] = upickle.default.macroRW

    /** The generator framework needs a reader for every parameter field; this is not a user-facing knob — the frames
      * live in `nzea_device.DeviceFrames` and only the bridge flow reads them.
      */
    given TokensReader.Simple[FramedRange] with
        def shortName = "frame-json"

        def read(strs: Seq[String]): Either[String, FramedRange] =
            strs.lastOption.toRight("a frame requires a value").flatMap { text =>
                scala.util.Try(upickle.default.read[FramedRange](text)).toEither.left.map(_.getMessage)
            }

case class LiteBusResidencyParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    clockHz: Int,
    frames: Seq[FramedRange],
    counters: Boolean,
    stats: StatScope
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(clockHz >= 1, s"clockHz must be >= 1, got $clockHz")
    require(frames.nonEmpty, "a residency without frames adds nothing; leave the port bare instead")

    require(
        frames.forall(spec =>
            spec.range.base >= 0 && spec.range.size >= 1 && spec.range.base + spec.range.size <= (BigInt(
                1
            ) << addrWidth)
        ),
        s"every frame range must fit the address width $addrWidth"
    )

given upickle.default.ReadWriter[LiteBusResidencyParameter] = upickle.default.macroRW

class LiteBusResidencyLayers(parameter: LiteBusResidencyParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class LiteBusResidencyIO(parameter: LiteBusResidencyParameter) extends HWBundle(parameter):
    private val bus = new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth)

    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The fabric side: the module is its slave. */
    val in = Flipped(bus)

    /** The device side: the module is its master. */
    val out = Aligned(bus)

class LiteBusResidencyProbe(parameter: LiteBusResidencyParameter)
    extends DVBundle[LiteBusResidencyParameter, LiteBusResidencyLayers](parameter)

/** The residence a slow wire imposes: on the ranges the table names, one access is one *frame* — the request fires as
  * the wire takes it, and the answer is held until the frame's own arithmetic ([[FrameTiming.cycles]]) has run out. The
  * window stays busy for the frame's whole length, one transaction at a time, so a frame caps throughput as well as
  * latency — what distinguishes this from [[LiteBusTiming]], whose delay is a line.
  *
  * A hit request waits for the port to fall quiet first: the answer captured for a frame must be the frame's own, and
  * the pipelined path behind can still be carrying an earlier (fast) access's answer otherwise.
  *
  * Accesses outside the table pass through with no added cycle, except while a frame holds the port — one port is one
  * wire's worth of concurrency, and the table's ranges share it.
  */
@generator
object LiteBusResidency
    extends Generator[LiteBusResidencyParameter, LiteBusResidencyLayers, LiteBusResidencyIO, LiteBusResidencyProbe]:

    /** The module is named by its whole interface — the table is the body — so one name is one body. */
    override def moduleName(parameter: LiteBusResidencyParameter): String =
        val frames = parameter.frames
            .map(spec =>
                s"${spec.range.base.toString(16)}x${spec.range.size.toString(16)}c${spec.timing.cycles(parameter.clockHz)}"
            )
            .mkString("_")
        s"LiteBusResidency_$frames"

    /** The block name the residency's statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "residency"

    def architecture(parameter: LiteBusResidencyParameter) =
        val io = summon[Interface[LiteBusResidencyIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val entries = parameter.frames.map(spec => (spec.range, spec.timing.cycles(parameter.clockHz)))
        val cyclesW = log2Ceil(entries.map(_._2).max + 1)
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
        io.in.req.ready := io.out.req.ready & idle & !waitQuiet
        io.out.req.valid := io.in.req.valid & idle & !waitQuiet
        io.out.req.bits :<= io.in.req.bits

        val fire = io.in.req.valid & io.in.req.ready
        val started = fire & hit

        // The request side counts what is outstanding; the answer side takes one back.
        val outFire = io.out.req.valid & io.out.req.ready
        val respFire = io.out.resp.valid & io.out.resp.ready
        when(outFire & !respFire) {
            outstanding := addWrap(outstanding, 1.U(outstandingW), outstandingW)
        }.otherwise {
            when(!outFire & respFire) {
                outstanding := subWrap(outstanding, 1.U(outstandingW), outstandingW)
            }
        }

        // While a frame is in flight the answer side captures; otherwise it is the upstream's, straight through.
        val capture = busy & io.out.resp.valid & !heldValid
        io.out.resp.ready := busy ? (!heldValid, io.in.resp.ready)
        when(capture) {
            heldValid := true.B
            heldData := io.out.resp.bits.data
            heldUser := io.out.resp.bits.user
            heldId := io.out.resp.bits.id
        }

        // The frame's length runs out: the captured answer becomes visible, and the port frees when it is taken.
        val release = busy & (remain === 0.U(cyclesW))
        io.in.resp.valid := busy ? ((heldValid & release), io.out.resp.valid)
        io.in.resp.bits.data := busy ? (heldData, io.out.resp.bits.data)
        io.in.resp.bits.user := busy ? (heldUser, io.out.resp.bits.user)
        io.in.resp.bits.id := busy ? (heldId, io.out.resp.bits.id)

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

        def counters(): Unit =
            val frames = parameter.stats.stat("frames", BusRegions.Bus, "frames started (one access each)")
            frames.inc(started)
            val busyCycles = parameter.stats.stat("busy", BusRegions.Bus, "cycles the port is held by a frame")
            busyCycles.inc(busy)

        if parameter.counters then counters()
