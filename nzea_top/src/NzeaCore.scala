package nzea_top

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.CoreConfig
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

/** The core level's top: the core, plus the shape the dump flow asked for.
  *
  * `sim` wires the core's interfaces straight into the bridges that call `bus_read` / `bus_write` / `commit_trace` — the
  * module remu loads, with `clock` and `reset` named and polarised exactly as its Verilator wrapper drives them (`reset`
  * high for the first hundred cycles, then low). Without it the core's interfaces are the top's own ports and the design
  * is what Yosys reads. The one conversion at this boundary is the reset: every module in this tree takes an active-low
  * `resetN`, so the active-high boundary signal is inverted here and nowhere else.
  */
@generator
object NzeaCore extends Generator[NzeaCoreParameter, NzeaCoreLayers, NzeaCoreIO, NzeaCoreProbe]:
  override def moduleName(parameter: NzeaCoreParameter): String = "NzeaCore"

  def architecture(parameter: NzeaCoreParameter) =
    val io = summon[Interface[NzeaCoreIO]]
    given ClockScope = ClockScope.posedge(io.clock)

    val c = parameter.config
    val resetN: Node[Reset] = (!io.reset.asBool).asReset

    val core = Core.instantiate(CoreParameter(c.isa, c.defaultPc, c.sim, parameter.mmioRanges))
    core.io.clock := io.clock
    core.io.resetN := resetN

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

      ibus.io.bus :<>= core.io.ibus
      dbus.io.bus :<>= core.io.dbus
      commit.io.commit_msg :<= core.io.commit_msg
    else
      io.ibus.get :<>= core.io.ibus
      io.dbus.get :<>= core.io.dbus
      io.commit_msg.get :<= core.io.commit_msg
