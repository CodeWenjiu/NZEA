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

        back.io.flush :<= middle.io.flush
        front.io.flush :<= middle.io.flush

        middle.io.decoded :<>= front.io.out

        back.io.moved :<>= middle.io.moved
        middle.io.retire :<>= back.io.retire
        middle.io.forward :<= back.io.forward

        front.io.bp_update.zip(back.io.bp_update).foreach((port, training) => port :<= training)

        io.ibus :<>= front.io.ibus.buffered("ibusBuffer", BufferPolicy.Skid, Some(middle.io.flush.valid))
        io.dbus :<>= back.io.dbus.buffered("dbusBuffer", BufferPolicy.Slice)
        io.commit_msg :<= middle.io.commit

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
