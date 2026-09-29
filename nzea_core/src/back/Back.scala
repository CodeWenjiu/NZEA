package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.back.mem.{MemPath, MemPathParameter}
import nzea_core.isa.{Backend, Isa}
import nzea_rtl.{RtlFlow, StatScope}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BackParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, mmioRanges: Seq[String], stats: StatScope)
    extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[BackParameter] = upickle.default.macroRW

class BackLayers(parameter: BackParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class BackIO(parameter: BackParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(new IssueSlot(new IssueUop(parameter.config)))
  val complete = Aligned(ValidIO(new PathResult(width)))
  val dbus = Aligned(MemPath.dbusType(parameter.config))

class BackProbe(parameter: BackParameter) extends DVBundle[BackParameter, BackLayers](parameter)

/** Backend: the execute stage's units — it routes the issued uop to its path and merges the single completion back to
  * the middle.
  *
  * No pipeline state lives here: the middle holds the uop (its IS/EX register) and latches what completes (its
  * write-back register), so this module is the merge plus each path's own latency — the multiplier's stages, the
  * divider's loop, the memory's response. The routing test is the whole job at this level: which path owns a uop is
  * decode's `Backend`, so a path grows its own scheduler without this file noticing.
  */
@generator
object Back extends Generator[BackParameter, BackLayers, BackIO, BackProbe]:
  override def moduleName(parameter: BackParameter): String = "Back"

  /** The block name the backend's statistics are attributed to; the parent's scope takes it as a segment. */
  val Source: String = "back"

  def architecture(parameter: BackParameter) =
    val io = summon[Interface[BackIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val c = parameter.config
    val uopValid = io.issue.valid
    val uopFirst = io.issue.first
    val isMem = io.issue.bits.backend === Backend.MEM.lit.U(Backend.width)

    val int =
      IntPath.instantiate(IntPathParameter(c.isa, c.defaultPc, parameter.flow, parameter.stats.child(IntPath.Source)))
    val mem = MemPath.instantiate(MemPathParameter(c.isa, c.defaultPc, parameter.mmioRanges))

    int.io.clock := io.clock
    int.io.resetN := io.resetN
    mem.io.clock := io.clock
    mem.io.resetN := io.resetN
    io.dbus :<>= mem.io.dbus

    int.io.issue.valid := uopValid & !isMem
    int.io.issue.first := uopFirst & !isMem
    int.io.issue.bits :<= io.issue.bits
    mem.io.issue.valid := uopValid & isMem
    mem.io.issue.first := uopFirst & isMem
    mem.io.issue.bits :<= io.issue.bits

    val done = (int.io.done & !isMem) | (mem.io.done & isMem)

    val result = Wire(new PathResult(width))
    when(isMem) { result :<= mem.io.result }.otherwise { result :<= int.io.result }

    // The completion: a level for as long as the held uop is finished, carrying what the middle's write-back register
    // samples on that level's edge.
    io.complete.valid := done
    io.complete.bits :<= result
