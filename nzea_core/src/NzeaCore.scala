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
import nzea_core.dpi.{
  CommitDpiBridge,
  CommitDpiBridgeParameter,
  DbusDpiBridge,
  DbusDpiBridgeParameter,
  IbusDpiBridge,
  IbusDpiBridgeParameter
}
import nzea_core.front.{Front, FrontParameter}
import nzea_core.front.ifu.Ifu
import nzea_core.middle.{CommitMsg, Middle, MiddleParameter}
import nzea_rtl.{LiteBusRO, LiteBusRW}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The core top, in the two shapes its consumers need.
  *
  * `sim` wires the buses straight into the bridges that call `bus_read` / `bus_write` / `commit_trace` — the module
  * remu loads, with `clock` and `reset` named and polarised exactly as remu's Verilator wrapper drives them (`reset`
  * high for the first hundred cycles, then low). Without it the buses are the top's own ports and the design is what
  * Yosys reads. The one conversion at this boundary is the reset: every module in this tree takes an active-low
  * `resetN`, so the active-high boundary signal is inverted here and nowhere else.
  */
case class NzeaCoreParameter(isa: String, defaultPc: Long, sim: Boolean, mmioRanges: Seq[String]) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc, sim)

given upickle.default.ReadWriter[NzeaCoreParameter] = upickle.default.macroRW

class NzeaCoreLayers(parameter: NzeaCoreParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class NzeaCoreIO(parameter: NzeaCoreParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val ibus: Option[BundleField[LiteBusRO]] =
    Option.when(!parameter.sim)(Aligned(Ifu.busType(parameter.config)))
  val dbus: Option[BundleField[LiteBusRW]] =
    Option.when(!parameter.sim)(Aligned(MemPath.dbusType(parameter.config)))
  val commit_msg: Option[BundleField[ValidIO[CommitMsg]]] =
    Option.when(!parameter.sim)(Aligned(ValidIO(new CommitMsg(width))))

class NzeaCoreProbe(parameter: NzeaCoreParameter) extends DVBundle[NzeaCoreParameter, NzeaCoreLayers](parameter)

@generator
object NzeaCore extends Generator[NzeaCoreParameter, NzeaCoreLayers, NzeaCoreIO, NzeaCoreProbe]:
  override def moduleName(parameter: NzeaCoreParameter): String = "NzeaCore"

  def architecture(parameter: NzeaCoreParameter) =
    val io = summon[Interface[NzeaCoreIO]]
    given ClockScope = ClockScope.posedge(io.clock)

    val c = parameter.config
    val resetN: Node[Reset] = (!io.reset.asBool).asReset

    val front = Front.instantiate(FrontParameter(c.isa, c.defaultPc, c.sim))
    val middle = Middle.instantiate(MiddleParameter(c.isa, c.defaultPc, c.sim))
    val back = Back.instantiate(BackParameter(c.isa, c.defaultPc, c.sim, parameter.mmioRanges))

    front.io.clock := io.clock
    front.io.resetN := resetN
    middle.io.clock := io.clock
    middle.io.resetN := resetN
    back.io.clock := io.clock
    back.io.resetN := resetN

    middle.io.decoded :<>= front.io.out
    front.io.redirect :<= middle.io.redirect
    front.io.arf_write :<= middle.io.arf_write
    front.io.flush := middle.io.flush

    back.io.issue :<= middle.io.issue
    middle.io.report :<= back.io.report

    if c.sim then
      val ibus = IbusDpiBridge.instantiate(IbusDpiBridgeParameter(c.isa, c.defaultPc))
      val dbus = DbusDpiBridge.instantiate(DbusDpiBridgeParameter(c.isa, c.defaultPc))
      val commit = CommitDpiBridge.instantiate(CommitDpiBridgeParameter(c.isa, c.defaultPc))

      ibus.io.clock := io.clock
      ibus.io.resetN := resetN
      dbus.io.clock := io.clock
      dbus.io.resetN := resetN
      commit.io.clock := io.clock
      commit.io.resetN := resetN

      ibus.io.bus :<>= front.io.ibus
      dbus.io.bus :<>= back.io.dbus
      commit.io.commit_msg :<= middle.io.commit
    else
      io.ibus.get :<>= front.io.ibus
      io.dbus.get :<>= back.io.dbus
      io.commit_msg.get :<= middle.io.commit
