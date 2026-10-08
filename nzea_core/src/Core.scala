package nzea_core

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuConfig, BypassLevel, CoreConfig}
import nzea_core.stats.Regions
import nzea_core.back.{Back, BackParameter}
import nzea_core.back.mem.MemPath
import nzea_core.front.{Front, FrontParameter}
import nzea_core.front.ifu.Ifu
import nzea_core.isa.{CsrType, Gpr, Isa}
import nzea_core.middle.{Middle, MiddleParameter}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{buffered, BufferPolicy}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import nzea_sideband.CommitTrace
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class CoreParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    mmioRanges: Seq[String],
    bypass: BypassLevel,
    bpu: Option[BpuConfig],
    lsqDepth: Int,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[CoreParameter] = upickle.default.macroRW

class CoreLayers(parameter: CoreParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class CoreIO(parameter: CoreParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val ibus = Aligned(Ifu.busType(parameter.config))
    val dbus = Aligned(MemPath.dbusType(parameter.config))
    val commit_msg = Aligned(ValidIO(new CommitTrace(width, Gpr.indexWidth, CsrType.addrWidth)))

class CoreProbe(parameter: CoreParameter) extends DVBundle[CoreParameter, CoreLayers](parameter)

/** The core: the front, the middle and the two execution paths the middle dispatches to, with the interfaces it
  * actually has — an instruction bus, a data bus and the commit trace — as its own ports.
  *
  * Nothing here knows how the core is dumped or wired upwards: turning those interfaces into a top's ports or into
  * simulation bridges belongs to the level top (`nzea_top.NzeaCore`), and a tile instantiates this module as it is.
  * `sim` only decides whether the per-stage counters are built — instrumentation, not an interface.
  */
@generator
object Core extends Generator[CoreParameter, CoreLayers, CoreIO, CoreProbe]:
    override def moduleName(parameter: CoreParameter): String = "Core"

    /** The block name the core's statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "core"

    def architecture(parameter: CoreParameter) =
        val io = summon[Interface[CoreIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val front = Front.instantiate(FrontParameter(c.isa, c.defaultPc, parameter.bpu))
        val middle =
            Middle.instantiate(
                MiddleParameter(
                    c.isa,
                    c.defaultPc,
                    parameter.flow,
                    parameter.lsqDepth,
                    parameter.stats
                )
            )
        // The back is the execution subsystem: the dispatch, the two paths, the completion path and the forward
        // network. What crosses its boundary with the middle is the pipeline's own — the slot's uop down, the pace,
        // the retire stream and the column back.
        val back =
            Back.instantiate(
                BackParameter(
                    c.isa,
                    c.defaultPc,
                    parameter.flow,
                    parameter.bypass,
                    parameter.bpu,
                    parameter.lsqDepth,
                    parameter.mmioRanges,
                    parameter.stats
                )
            )

        front.io.clock := io.clock
        front.io.resetN := io.resetN
        middle.io.clock := io.clock
        middle.io.resetN := io.resetN
        back.io.clock := io.clock
        back.io.resetN := io.resetN

        middle.io.decoded :<>= front.io.out
        // The flush event is one bus, out of the middle and into every level that obeys it.
        front.io.flush :<= middle.io.flush
        // The predictor's training crosses the middle without touching it: the branch resolves in the back, the tables
        // live at the front, and nothing between the two consumes the outcome.
        front.io.bp_update :<= back.io.bp_update

        // The middle and the back: the execute slot's uop down, and the verdicts the middle's stages wait on back.
        back.io.issue :<>= middle.io.issue
        back.io.flush :<= middle.io.flush
        middle.io.pace := back.io.pace
        middle.io.retire :<= back.io.retire
        middle.io.offers :<= back.io.offers
        middle.io.commit_armed := back.io.commit_armed

        // The instruction request is buffered at the core's own boundary: the fetch's request valid follows the decode's
        // ready and its pc advances on the request's acceptance, so a host wiring the port live into a fabric would let
        // the fabric's ready reach back into the pc/predictor path. `Skid` cuts that — the buffer's ready is its own
        // state — while one request per cycle still goes through; the response channel stays combinational.
        io.ibus :<>= front.io.ibus.buffered("ibusBuffer", BufferPolicy.Skid)

        // The data request is buffered too, with the elastic slice: the LSU's address and data are live off the execute
        // register, so a host wiring them straight into a fabric would extend that cone through the fabric's
        // arbitration. The slice makes what the fabric sees state, and costs a cycle on every access's request path —
        // the load latency the in-order pipeline pays.
        io.dbus :<>= back.io.dbus.buffered("dbusBuffer", BufferPolicy.Slice)

        io.commit_msg :<= middle.io.commit

        /** The instruction port's bandwidth and what it buys: the port accepts one request and offers one response per
          * fetch, so `resp` against `fire` is the flushes' toll — a killed stream's response is consumed and digested,
          * never forwarded, which spent the port on an instruction that retired nothing.
          */
        def counters(): Unit =
            val req = parameter.stats.stat("ibus_req", Regions.Fetch, "fetch requests accepted")
            val resp = parameter.stats.stat("ibus_resp", Regions.Fetch, "fetch responses consumed")
            val stall = parameter.stats.stat("ibus_stall", Regions.Fetch, "cycles a fetch request waits for the bus")
            val fire = parameter.stats.stat("fetch_fire", Regions.Fetch, "instructions delivered to the middle")
            req.inc(io.ibus.req.valid & io.ibus.req.ready)
            resp.inc(io.ibus.resp.valid & io.ibus.resp.ready)
            stall.inc(io.ibus.req.valid & !io.ibus.req.ready)
            fire.inc(front.io.out.valid & front.io.out.ready)

        if parameter.flow.counters then counters()
