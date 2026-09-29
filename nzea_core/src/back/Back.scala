package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
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
  val report = Aligned(new BackOut(width))
  val dbus = Aligned(MemPath.dbusType(parameter.config))

class BackProbe(parameter: BackParameter) extends DVBundle[BackParameter, BackLayers](parameter)

/** Backend: routes the issue uop to its execution path and merges the single completion back to the middle.
  *
  * The routing test is the whole job here — which path owns a uop is decode's `Backend`, so a path grows its own
  * scheduler without this file noticing. The paths report a completion as a one-cycle pulse, and the exit register
  * latches the result with it; the pulse the middle retires arrives one cycle later. A flush is latched with the same
  * pulse, so it reaches the middle in the cycle the branch commits and carries the target the front restarts from.
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

    val flush = int.io.redirect.valid
    val done = (int.io.done & !isMem) | (mem.io.done & isMem)

    val result = Wire(new PathResult(width))
    when(isMem) { result :<= mem.io.result }.otherwise { result :<= int.io.result }

    // The paths report a completion as a one-cycle pulse, so that pulse is the latch enable: a level would still be
    // high when the next uop arrives, one cycle later in the steady state.
    val resultReg = Reg(new PathResult(width))
    val flushReg = RegInit(false.B)
    val redirectReg = Reg(UInt(width))
    when(done) {
      resultReg :<= result
      flushReg := flush
      redirectReg := int.io.redirect.bits
    }

    val donePulse = regNext(done, false.B)
    io.report.complete := done
    io.report.done := donePulse
    io.report.data := resultReg.data
    io.report.next_pc := resultReg.next_pc
    io.report.csr_type := resultReg.csr_type
    io.report.csr_data := resultReg.csr_data
    io.report.is_mmio := resultReg.is_mmio
    io.report.flush := donePulse & flushReg
    io.report.redirect_target := redirectReg
