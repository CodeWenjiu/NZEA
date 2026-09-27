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
import nzea_config.core.{CoreConfig, Isa}
import nzea_core.front.ifu.{FetchedInst, Ifu, IfuParameter}
import nzea_core.front.idu.{ArfWrite, Idu, IduOut, IduParameter}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class FrontParameter(isa: Isa, defaultPc: Long) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[FrontParameter] = upickle.default.macroRW

class FrontLayers(parameter: FrontParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class FrontIO(parameter: FrontParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val flush = Flipped(Bool())
  val ibus = Aligned(Ifu.busType(parameter.config))
  val redirect = Flipped(Valid(UInt(width)))
  val arf_write = Flipped(ValidIO(new ArfWrite(width)))
  val out = Aligned(Decoupled(new IduOut(parameter.config)))

class FrontProbe(parameter: FrontParameter) extends DVBundle[FrontParameter, FrontLayers](parameter)

/** Front-end: fetch ([[Ifu]]) and decode ([[Idu]]) in one module, with the pipeline register between them owned here.
  *
  * The decoded stream onto the boundary stays combinational: the register read happens in decode and the middle's idle
  * gate guarantees it follows every earlier commit's write-back. A registered boundary would capture the read cycles
  * before issue and hand the middle stale operands.
  *
  * `flush` is the core's broadcast and the register between the stages is the only thing here that obeys it: the
  * fetcher's cancellation is its epoch and the decoded stream onto the middle is gated by that same register.
  */
@generator
object Front extends Generator[FrontParameter, FrontLayers, FrontIO, FrontProbe]:
  override def moduleName(parameter: FrontParameter): String = "Front"

  def architecture(parameter: FrontParameter) =
    val io = summon[Interface[FrontIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val c = parameter.config
    val ifu = Ifu.instantiate(IfuParameter(c.isa, c.defaultPc))
    val idu = Idu.instantiate(IduParameter(c.isa, c.defaultPc))

    ifu.io.clock := io.clock
    ifu.io.resetN := io.resetN
    idu.io.clock := io.clock
    idu.io.resetN := io.resetN

    io.ibus :<>= ifu.io.ibus
    ifu.io.redirect :<>= io.redirect

    pipelineConnect(ifu.io.out, idu.io.in, io.flush)

    idu.io.arf_write :<>= io.arf_write
    io.out :<>= idu.io.out
