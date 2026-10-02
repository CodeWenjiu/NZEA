package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuLevel, BypassLevel, CoreConfig}
import nzea_core.back.int.{IntPath, IntPathParameter}
import nzea_core.back.mem.{MemPath, MemPathParameter}
import nzea_core.isa.{Backend, Isa}
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class BackParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    mmioRanges: Seq[String],
    bypass: BypassLevel,
    bpu: BpuLevel,
    stats: StatScope
) extends Parameter:
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
    val bypass = Flipped(new BypassBus(width))
    val dbus = Aligned(MemPath.dbusType(parameter.config))

    /** The resolved branches, for the front's predictor: the int path's own registers, already off the resolve cone. */
    val bp_update = Aligned(ValidIO(new BpUpdate(width)))

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
            IntPath.instantiate(
                IntPathParameter(
                    c.isa,
                    c.defaultPc,
                    parameter.flow,
                    parameter.stats.child(IntPath.Source),
                    parameter.bpu
                )
            )
        val mem = MemPath.instantiate(MemPathParameter(c.isa, c.defaultPc, parameter.mmioRanges))
        val bypass = Bypass.instantiate(BypassParameter(c.isa, c.defaultPc, parameter.bypass))

        int.io.clock := io.clock
        int.io.resetN := io.resetN
        mem.io.clock := io.clock
        mem.io.resetN := io.resetN
        bypass.io.clock := io.clock
        bypass.io.resetN := io.resetN
        io.dbus :<>= mem.io.dbus

        int.io.issue.valid := uopValid & !isMem
        int.io.issue.first := uopFirst & !isMem
        int.io.issue.bits :<= io.issue.bits
        mem.io.issue.valid := uopValid & isMem
        mem.io.issue.first := uopFirst & isMem
        mem.io.issue.bits :<= io.issue.bits

        // The resolved branches leave the back from here: the front's predictor listens, the middle does not pass them.
        io.bp_update :<= int.io.bp_update

        val done = (int.io.done & !isMem) | (mem.io.done & isMem)

        val result = Wire(new PathResult(width))
        when(isMem) { result :<= mem.io.result }.otherwise { result :<= int.io.result }

        // The completion: a level for as long as the held uop is finished, carrying what the middle's write-back register
        // samples on that level's edge.
        io.complete.valid := done
        io.complete.bits :<= result

        // The bypass network's raw producers: the completing instruction's result, with the destination it will write, born
        // here — and the retiring instruction's write, carried over the bus from the middle. The request side comes from the
        // middle's read too, and the answers go back over the same bus.
        bypass.io.exec.valid := io.complete.valid
        bypass.io.exec.bits.rd_index := io.issue.bits.rd_index
        bypass.io.exec.bits.data := io.complete.bits.data
        bypass.io.commit :<= io.bypass.commit
        bypass.io.rs1_index := io.bypass.rs1_index
        bypass.io.rs2_index := io.bypass.rs2_index
        io.bypass.rs1 :<= bypass.io.rs1
        io.bypass.rs2 :<= bypass.io.rs2
