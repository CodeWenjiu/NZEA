package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.isa.{Gpr, Isa}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One raw producer's offer: the register it is writing and the value it has. */
class BypassValue(width: Int) extends Bundle:
    val rd_index = Aligned(UInt(Gpr.indexWidth))
    val data = Aligned(UInt(width))

/** The reads' end of the bypass network: which registers the issue unit is reading, the write the middle is retiring —
  * a producer that lives in the middle, so the network cannot see it from the back — and per read the value a producer
  * has for it.
  *
  * Declared from the requester's view — the middle drives the indexes and the retiring write, the network drives the
  * two answers — so a `Flipped` port on the network's side faces it.
  */
class BypassBus(width: Int) extends Bundle:
    val rs1_index = Aligned(UInt(Gpr.indexWidth))
    val rs2_index = Aligned(UInt(Gpr.indexWidth))

    /** The retiring instruction's write: the middle's write-back register, brought over so the network owns every
      * producer the read's ladder includes.
      */
    val commit = Aligned(ValidIO(new BypassValue(width)))

    val rs1 = Flipped(ValidIO(UInt(width)))
    val rs2 = Flipped(ValidIO(UInt(width)))

case class BypassParameter(isa: Isa, defaultPc: Long, bypass: BypassLevel) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[BypassParameter] = upickle.default.macroRW

class BypassLayers(parameter: BypassParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BypassIO(parameter: BypassParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The completing instruction's result — the youngest raw producer, born in this module's parent. */
    val exec = Flipped(ValidIO(new BypassValue(width)))

    /** The retiring instruction's write — the older raw producer, carried in from the middle. */
    val commit = Flipped(ValidIO(new BypassValue(width)))

    /** The reads asking: the two register indexes the issue unit is looking up. */
    val rs1_index = Flipped(UInt(Gpr.indexWidth))
    val rs2_index = Flipped(UInt(Gpr.indexWidth))

    /** The answers: valid when a producer is offering the asked register this cycle. */
    val rs1 = Aligned(ValidIO(UInt(width)))
    val rs2 = Aligned(ValidIO(UInt(width)))

class BypassProbe(parameter: BypassParameter) extends DVBundle[BypassParameter, BypassLayers](parameter)

/** The bypass network: raw producers offer `(rd, value)` and a read asks `(rs1_index, rs2_index)`, answered with a
  * per-read `ValidIO` — the consumer selects between the answer and the register file.
  *
  * The matching lives here, not at the consumer, so the raw producers do not know who reads them and the consumer does
  * not know who produces: both sides only see this bus. The producers form the forwarding ladder — the completing
  * instruction, the retiring one, then the file — and the level keeps the youngest prefix of it: at `Commit` only the
  * retiring write is a source, at `Execute` the completing result joins it ahead of it (a collision goes to the younger
  * producer), and below both the source list is empty: the answers stay zero and the constant folds out of whoever
  * asked.
  */
@generator
object Bypass extends Generator[BypassParameter, BypassLayers, BypassIO, BypassProbe]:
    override def moduleName(parameter: BypassParameter): String = "Bypass"

    def architecture(parameter: BypassParameter) =
        val io = summon[Interface[BypassIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val indexWidth = Gpr.indexWidth

        def offer(valid: Referable[Bool], rdIndex: Referable[UInt], data: Referable[UInt]): ForwardSource =
            ForwardSource(valid & (rdIndex =/= 0.U(indexWidth)), rdIndex, data)

        // The ladder cut to the level's prefix, youngest first: a read prefers the completing instruction to the retiring
        // one. Offers the level does not reach are never built.
        val sources: Seq[ForwardSource] =
            Seq(
                Option.when(parameter.bypass.execute)(offer(io.exec.valid, io.exec.bits.rd_index, io.exec.bits.data)),
                Option.when(parameter.bypass.commit)(
                    offer(io.commit.valid, io.commit.bits.rd_index, io.commit.bits.data)
                )
            ).flatten

        def answers(index: Referable[UInt]): (Referable[Bool], Referable[UInt]) =
            val hits = sources.map(source => source.armed & (source.index === index))
            val hit = hits.foldLeft(false.B: Referable[Bool])(_ | _)
            (hit, forwardRead(index, 0.U(width), sources))

        val (rs1Hit, rs1Data) = answers(io.rs1_index)
        val (rs2Hit, rs2Data) = answers(io.rs2_index)
        io.rs1.valid := rs1Hit
        io.rs1.bits := rs1Data
        io.rs2.valid := rs2Hit
        io.rs2.bits := rs2Data
