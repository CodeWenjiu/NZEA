package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.isa.{Backend, Isa}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class DispatchParameter(isa: Isa, defaultPc: Long) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[DispatchParameter] = upickle.default.macroRW

/** What the dispatch's merge concluded about the held uop: whether it is finished — with the value it produced — and
  * whether its slot may move on. The two are separate facts: a memory op's slot is released when its request is
  * accepted, long before its answer lands.
  */
class Merged(width: Int) extends Bundle:
    val complete = Aligned(ValidIO(new PathResult(width)))
    val release = Aligned(Bool())

class DispatchLayers(parameter: DispatchParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class DispatchIO(parameter: DispatchParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    val issue = Flipped(new IssueSlot(new ExecSlot(parameter.config)))

    val int = Aligned(new IntFace(parameter.config))
    val mem = Aligned(new MemFace(parameter.config))

    val merged = Aligned(new Merged(width))

class DispatchProbe(parameter: DispatchParameter) extends DVBundle[DispatchParameter, DispatchLayers](parameter)

/** The dispatch: the one place the issued uop's path is decided, and the two faces that decision drives.
  *
  * Decode's `Backend` says which path owns a uop, and one `is_mem` test spends that fact on both faces — the routing
  * and the merge cannot drift apart. Combinational: the uop is the issue stage's register and the reports are the
  * paths' own levels.
  */
@generator
object Dispatch extends Generator[DispatchParameter, DispatchLayers, DispatchIO, DispatchProbe]:
    override def moduleName(parameter: DispatchParameter): String = "Dispatch"

    def architecture(parameter: DispatchParameter) =
        val io = summon[Interface[DispatchIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val uopValid = io.issue.valid
        val uopFirst = io.issue.first
        val isMem = io.issue.bits.backend === Backend.MEM.lit.U(Backend.width)

        io.int.issue.valid := uopValid & !isMem
        io.int.issue.first := uopFirst & !isMem
        io.int.issue.bits :<= io.issue.bits
        io.mem.issue.valid := uopValid & isMem
        io.mem.issue.first := uopFirst & isMem
        io.mem.issue.bits :<= io.issue.bits

        val complete = (io.int.done & !isMem) | (io.mem.done & isMem)
        val release = (io.int.done & !isMem) | (io.mem.slotRelease & isMem)

        val result = Wire(new PathResult(width))
        when(isMem) { result :<= io.mem.result }.otherwise { result :<= io.int.result }

        io.merged.complete.valid := complete
        io.merged.complete.bits :<= result
        io.merged.release := release
