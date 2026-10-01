package nzea_top

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.{NzeaCoreParameter, given}
import nzea_core.{Core, CoreParameter}
import nzea_core.back.mem.MemPath
import nzea_core.front.ifu.Ifu
import nzea_core.middle.CommitMsg
import nzea_rtl.{LiteBusRO, LiteBusRW}
import nzea_rtl.stdlib.*
import nzea_top.dpi.{
    CommitDpiBridge,
    CommitDpiBridgeParameter,
    DbusDpiBridge,
    DbusDpiBridgeParameter,
    IbusDpiBridge,
    IbusDpiBridgeParameter
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

class NzeaCoreLayers(parameter: NzeaCoreParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class NzeaCoreIO(parameter: NzeaCoreParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val reset = Flipped(Reset())

    val ibus: Option[BundleField[LiteBusRO]] =
        Option.when(!parameter.flow.bridges)(Aligned(Ifu.busType(parameter.config)))

    val dbus: Option[BundleField[LiteBusRW]] =
        Option.when(!parameter.flow.bridges)(Aligned(MemPath.dbusType(parameter.config)))

    val commit_msg: Option[BundleField[ValidIO[CommitMsg]]] =
        Option.when(!parameter.flow.bridges)(Aligned(ValidIO(new CommitMsg(width))))

class NzeaCoreProbe(parameter: NzeaCoreParameter) extends DVBundle[NzeaCoreParameter, NzeaCoreLayers](parameter)

/** The core level's top: the core, plus the shape the flow asked for.
  *
  * A flow that reads the design through DPI gets the core's interfaces wired straight into the bridges that call
  * `bus_read` / `bus_write` / `commit_trace` — the module remu loads, with `clock` and `reset` named and polarised
  * exactly as its Verilator wrapper drives them (`reset` high for the first hundred cycles, then low). Every other flow
  * reads the design as hardware, so the interfaces are the top's own ports. The one conversion at this boundary is the
  * reset: every module in this tree takes an active-low `resetN`, so the active-high boundary signal is inverted here
  * and nowhere else.
  */
@generator
object NzeaCore extends Generator[NzeaCoreParameter, NzeaCoreLayers, NzeaCoreIO, NzeaCoreProbe]:
    override def moduleName(parameter: NzeaCoreParameter): String = "NzeaCore"

    def architecture(parameter: NzeaCoreParameter) =
        val io = summon[Interface[NzeaCoreIO]]
        given ClockScope = ClockScope.posedge(io.clock)

        val c = parameter.config
        val resetN: Node[Reset] = (!io.reset.asBool).asReset

        val core =
            Core.instantiate(
                CoreParameter(
                    c.isa,
                    c.defaultPc,
                    parameter.flow,
                    parameter.mmioRanges,
                    parameter.bypass,
                    parameter.bpu,
                    parameter.stats.child(Core.Source)
                )
            )
        core.io.clock := io.clock
        core.io.resetN := resetN

        if parameter.flow.bridges then
            val ibus = IbusDpiBridge.instantiate(IbusDpiBridgeParameter(c.isa, c.defaultPc))
            val dbus = DbusDpiBridge.instantiate(DbusDpiBridgeParameter(c.isa, c.defaultPc))
            val commit = CommitDpiBridge.instantiate(CommitDpiBridgeParameter(c.isa, c.defaultPc))

            ibus.io.clock := io.clock
            ibus.io.resetN := resetN
            dbus.io.clock := io.clock
            dbus.io.resetN := resetN
            commit.io.clock := io.clock
            commit.io.resetN := resetN

            ibus.io.bus :<>= core.io.ibus
            dbus.io.bus :<>= core.io.dbus
            commit.io.commit_msg :<= core.io.commit_msg
        else
            io.ibus.get :<>= core.io.ibus
            io.dbus.get :<>= core.io.dbus
            io.commit_msg.get :<= core.io.commit_msg
