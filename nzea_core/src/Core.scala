package nzea_core

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BypassConfig, CoreConfig}
import nzea_core.back.{Back, BackParameter}
import nzea_core.back.mem.MemPath
import nzea_core.front.{Front, FrontParameter}
import nzea_core.front.ifu.Ifu
import nzea_core.isa.Isa
import nzea_core.middle.{CommitMsg, Middle, MiddleParameter}
import nzea_rtl.{RtlFlow, StatScope}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class CoreParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    mmioRanges: Seq[String],
    bypass: BypassConfig,
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
  val commit_msg = Aligned(ValidIO(new CommitMsg(width)))

class CoreProbe(parameter: CoreParameter) extends DVBundle[CoreParameter, CoreLayers](parameter)

/** The core: front, middle and back, with the interfaces it actually has — an instruction bus, a data bus and the
  * commit trace — as its own ports.
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
    val front = Front.instantiate(FrontParameter(c.isa, c.defaultPc))
    val middle =
      Middle.instantiate(
        MiddleParameter(c.isa, c.defaultPc, parameter.flow, parameter.bypass, parameter.stats.child(Middle.Source))
      )
    val back =
      Back.instantiate(
        BackParameter(c.isa, c.defaultPc, parameter.flow, parameter.mmioRanges, parameter.stats.child(Back.Source))
      )

    front.io.clock := io.clock
    front.io.resetN := io.resetN
    middle.io.clock := io.clock
    middle.io.resetN := io.resetN
    back.io.clock := io.clock
    back.io.resetN := io.resetN

    middle.io.decoded :<>= front.io.out
    front.io.redirect :<= middle.io.redirect
    front.io.flush := middle.io.flush

    back.io.issue :<= middle.io.issue
    middle.io.complete :<= back.io.complete

    io.ibus :<>= front.io.ibus
    io.dbus :<>= back.io.dbus
    io.commit_msg :<= middle.io.commit
