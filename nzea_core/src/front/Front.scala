package nzea_core.front

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.Decoupled
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuConfig, CoreConfig}
import nzea_core.back.BpUpdate
import nzea_core.front.idu.{Idu, IduOut, IduParameter}
import nzea_core.front.ifu.{FetchedInst, Ifu, IfuParameter}
import nzea_core.isa.Isa
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class FrontParameter(isa: Isa, defaultPc: Long, bpu: Option[BpuConfig]) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[FrontParameter] = upickle.default.macroRW

class FrontLayers(parameter: FrontParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class FrontIO(parameter: FrontParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val flush = Flipped(new FlushBus(width))
    val ibus = Aligned(Ifu.busType(parameter.config))

    /** The predictor's training, when a predictor is built: the port follows the config on both ends, so a design
      * without one carries neither the wires nor the back's training registers.
      */
    val bp_update: Option[BundleField[ValidIO[BpUpdate]]] =
        Option.when(parameter.bpu.isDefined)(Flipped(Valid(new BpUpdate(width))))

    val out = Aligned(Decoupled(new IduOut(parameter.config)))

class FrontProbe(parameter: FrontParameter) extends DVBundle[FrontParameter, FrontLayers](parameter)

/** Front-end: fetch ([[Ifu]]) and decode ([[Idu]]), with the pipeline register between them owned here.
  *
  * The decoded stream onto the boundary stays combinational — the middle owns the read, so what crosses is the
  * decoded instruction and a gate that holds it back while the middle's decode slot is busy. `flush` is the core's one
  * broadcast bus: the fetcher restarts from its `next_pc` and cancels its own stream through the epoch, while the if/id
  * register is the only thing here that obeys `valid`.
  */
@generator
object Front extends Generator[FrontParameter, FrontLayers, FrontIO, FrontProbe]:
    override def moduleName(parameter: FrontParameter): String = "Front"

    def architecture(parameter: FrontParameter) =
        val io = summon[Interface[FrontIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val ifu = Ifu.instantiate(IfuParameter(c.isa, c.defaultPc, parameter.bpu))
        val idu = Idu.instantiate(IduParameter(c.isa, c.defaultPc))

        ifu.io.clock := io.clock
        ifu.io.resetN := io.resetN
        idu.io.clock := io.clock
        idu.io.resetN := io.resetN

        io.ibus :<>= ifu.io.ibus
        ifu.io.redirect :<>= io.flush
        if parameter.bpu.isDefined then ifu.io.bp_update.get :<>= io.bp_update.get

        pipelineConnect(ifu.io.out, idu.io.in, io.flush, "ifid", FlushHandover.Consume)

        io.out :<>= idu.io.out
