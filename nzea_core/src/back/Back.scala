package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.CoreConfig
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.back.mem.{MemPath, MemPathParameter}
import nzea_core.isa.Backend
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BackParameter(isa: String, defaultPc: Long, sim: Boolean, mmioRanges: Seq[String]) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc, sim)

given upickle.default.ReadWriter[BackParameter] = upickle.default.macroRW

class BackLayers(parameter: BackParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class BackIO(parameter: BackParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(Valid(new IssueUop(width)))
  val report = Aligned(new BackOut(width))
  val dbus = Aligned(MemPath.dbusType(parameter.config))

class BackProbe(parameter: BackParameter) extends DVBundle[BackParameter, BackLayers](parameter)

/** Backend: routes the issue uop to its execution path and merges the single completion back to the middle.
  *
  * The routing test is the whole job here — which path owns a uop is decode's `Backend`, so a path grows its own
  * scheduler without this file noticing. The paths report a completion level, and the edge is taken exactly once, here:
  * the exit register latches the result and the pulse the middle retires arrives with it, one cycle later. A flush is
  * latched with the same edge, so it reaches the middle in the cycle the branch commits and carries the target the
  * front restarts from.
  */
@generator
object Back extends Generator[BackParameter, BackLayers, BackIO, BackProbe]:
  override def moduleName(parameter: BackParameter): String = "Back"

  def architecture(parameter: BackParameter) =
    val io = summon[Interface[BackIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val c = parameter.config
    val uopValid = io.issue.valid
    val isMem = io.issue.bits.backend === Backend.MEM.lit.U(Backend.width)

    val int = IntPath.instantiate(IntPathParameter(c.isa, c.defaultPc, c.sim))
    val mem = MemPath.instantiate(MemPathParameter(c.isa, c.defaultPc, c.sim, parameter.mmioRanges))

    int.io.clock := io.clock
    int.io.resetN := io.resetN
    mem.io.clock := io.clock
    mem.io.resetN := io.resetN
    io.dbus :<>= mem.io.dbus

    int.io.issue.valid := uopValid & !isMem
    int.io.issue.bits :<= io.issue.bits
    mem.io.issue.valid := uopValid & isMem
    mem.io.issue.bits :<= io.issue.bits

    val flush = int.io.redirect.valid
    val done = (int.io.done & !isMem) | (mem.io.done & isMem)

    val result = Wire(new PathResult(width))
    when(isMem) { result :<= mem.io.result }.otherwise { result :<= int.io.result }

    // The completion edge, taken once: the result the pulse belongs to is the one latched with the edge.
    val doneEdge = done & !regNext(done)
    val resultReg = Reg(new PathResult(width))
    val flushReg = RegInit(false.B)
    val redirectReg = Reg(UInt(width))
    when(doneEdge) {
      resultReg :<= result
      flushReg := flush
      redirectReg := int.io.redirect.bits
    }

    val donePulse = regNext(doneEdge, false.B)
    io.report.done := donePulse
    io.report.data := resultReg.data
    io.report.next_pc := resultReg.next_pc
    io.report.csr_type := resultReg.csr_type
    io.report.csr_data := resultReg.csr_data
    io.report.is_mmio := resultReg.is_mmio
    io.report.flush := donePulse & flushReg
    io.report.redirect_target := redirectReg
