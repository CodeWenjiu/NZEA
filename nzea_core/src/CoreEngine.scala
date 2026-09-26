package nzea_core

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.CoreConfig
import nzea_core.back.{Back, BackParameter}
import nzea_core.back.mem.MemPath
import nzea_core.front.{Front, FrontParameter}
import nzea_core.front.ifu.Ifu
import nzea_core.middle.{CommitMsg, Middle, MiddleParameter}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class CoreEngineParameter(isa: String, defaultPc: Long, sim: Boolean, mmioRanges: Seq[String]) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc, sim)

given upickle.default.ReadWriter[CoreEngineParameter] = upickle.default.macroRW

class CoreEngineLayers(parameter: CoreEngineParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class CoreEngineIO(parameter: CoreEngineParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val ibus = Aligned(Ifu.busType(parameter.config))
  val dbus = Aligned(MemPath.dbusType(parameter.config))
  val commit_msg = Aligned(ValidIO(new CommitMsg(width)))

class CoreEngineProbe(parameter: CoreEngineParameter) extends DVBundle[CoreEngineParameter, CoreEngineLayers](parameter)

/** The core: front, middle and back, with the interfaces it actually has — an instruction bus, a data bus and the
  * commit trace — as its own ports.
  *
  * Nothing here knows how the core is dumped or wired upwards: turning those interfaces into a top's ports or into
  * simulation bridges belongs to the level top (`nzea_top.NzeaCore`), and a tile instantiates this module as it is.
  * `sim` only decides whether the per-stage counters are built — instrumentation, not an interface.
  */
@generator
object CoreEngine extends Generator[CoreEngineParameter, CoreEngineLayers, CoreEngineIO, CoreEngineProbe]:
  override def moduleName(parameter: CoreEngineParameter): String = "CoreEngine"

  def architecture(parameter: CoreEngineParameter) =
    val io = summon[Interface[CoreEngineIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val c = parameter.config
    val front = Front.instantiate(FrontParameter(c.isa, c.defaultPc, c.sim))
    val middle = Middle.instantiate(MiddleParameter(c.isa, c.defaultPc, c.sim))
    val back = Back.instantiate(BackParameter(c.isa, c.defaultPc, c.sim, parameter.mmioRanges))

    front.io.clock := io.clock
    front.io.resetN := io.resetN
    middle.io.clock := io.clock
    middle.io.resetN := io.resetN
    back.io.clock := io.clock
    back.io.resetN := io.resetN

    middle.io.decoded :<>= front.io.out
    front.io.redirect :<= middle.io.redirect
    front.io.arf_write :<= middle.io.arf_write
    front.io.flush := middle.io.flush

    back.io.issue :<= middle.io.issue
    middle.io.report :<= back.io.report

    io.ibus :<>= front.io.ibus
    io.dbus :<>= back.io.dbus
    io.commit_msg :<= middle.io.commit
