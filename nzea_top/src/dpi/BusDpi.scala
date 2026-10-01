package nzea_top.dpi

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.mem.{DbusUser, MemPath}
import nzea_core.front.ifu.{IbusUser, Ifu}
import nzea_core.isa.Isa
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The instruction bus's bridge to remu's `bus_read`: one outstanding request, answered from an elastic one-slot
  * register.
  *
  * `bus_read` is side-effect free, so it is called combinationally on the request fire and its result captured. The
  * slot answers one request per cycle at steady state: a request is accepted while the slot is free and also in the
  * cycle the presented response is taken — the same cycle that fills the slot again (the fill wins the handover), so
  * the rounding is a per-cycle flow, not a two-cycle alternation. Ready falls only while a response waits to be taken;
  * reset refuses traffic like any other slave in this design. The bridge never cancels anything — the fetcher's epoch
  * does that, and a request the fetcher abandons is answered into a slot it will simply drain.
  */
case class IbusDpiBridgeParameter(isa: Isa, defaultPc: Long) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IbusDpiBridgeParameter] = upickle.default.macroRW

class IbusDpiBridgeLayers(parameter: IbusDpiBridgeParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IbusDpiBridgeIO(parameter: IbusDpiBridgeParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(Ifu.busType(parameter.config))

class IbusDpiBridgeProbe(parameter: IbusDpiBridgeParameter)
    extends DVBundle[IbusDpiBridgeParameter, IbusDpiBridgeLayers](parameter)

@generator
object IbusDpiBridge
    extends Generator[IbusDpiBridgeParameter, IbusDpiBridgeLayers, IbusDpiBridgeIO, IbusDpiBridgeProbe]:
    override def moduleName(parameter: IbusDpiBridgeParameter): String = "IbusDpiBridge"

    def architecture(parameter: IbusDpiBridgeParameter) =
        val io = summon[Interface[IbusDpiBridgeIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val userWidth = IbusUser.userWidth(width)

        val respValid = RegInit(false.B)
        val respData = Reg(UInt(width))
        val respUser = Reg(UInt(userWidth))
        val respId = Reg(UInt(Ifu.busIdWidth))

        // `resetN` is active low, so ready is gated on it *high*: a slave refuses traffic while it is being reset. The
        // slot is elastic — taken-and-refilled in the same cycle is a handover, which is what keeps the round trip at one
        // request per cycle.
        io.bus.req.ready := (!respValid | io.bus.resp.ready) & io.resetN.asBool
        val fire = io.bus.req.valid & io.bus.req.ready
        val rdata = dpiUnclockedCall("bus_read", UInt(width), Some(Seq("addr")), Some("rdata"))(
            fire,
            io.bus.req.bits.addr
        )

        // A fill wins a same-cycle handover: the taken response leaves with the core, the accepted one takes its place.
        when(fire) {
            respValid := true.B
            respData := rdata
            respUser := io.bus.req.bits.user
            respId := io.bus.req.bits.id
        }.otherwise {
            when(respValid & io.bus.resp.ready) {
                respValid := false.B
            }
        }

        io.bus.resp.valid := respValid
        io.bus.resp.bits.data := respData
        io.bus.resp.bits.user := respUser
        io.bus.resp.bits.id := respId

/** The data bus's bridge to remu's `bus_read` / `bus_write`, with the same elastic one-slot response register.
  *
  * A load answers from `bus_read` and a store from `bus_write`, called on the clock edge next to the fire — a store's
  * response acknowledges the bus request rather than carrying data. `wstrb` is widened to the ABI's `int` here, because
  * the generated call takes the declared argument widths as they are.
  */
case class DbusDpiBridgeParameter(isa: Isa, defaultPc: Long) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[DbusDpiBridgeParameter] = upickle.default.macroRW

class DbusDpiBridgeLayers(parameter: DbusDpiBridgeParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class DbusDpiBridgeIO(parameter: DbusDpiBridgeParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(MemPath.dbusType(parameter.config))

class DbusDpiBridgeProbe(parameter: DbusDpiBridgeParameter)
    extends DVBundle[DbusDpiBridgeParameter, DbusDpiBridgeLayers](parameter)

@generator
object DbusDpiBridge
    extends Generator[DbusDpiBridgeParameter, DbusDpiBridgeLayers, DbusDpiBridgeIO, DbusDpiBridgeProbe]:
    override def moduleName(parameter: DbusDpiBridgeParameter): String = "DbusDpiBridge"

    def architecture(parameter: DbusDpiBridgeParameter) =
        val io = summon[Interface[DbusDpiBridgeIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width

        val respValid = RegInit(false.B)
        val respData = Reg(UInt(width))
        val respUser = Reg(UInt(DbusUser.userWidth))
        val respId = Reg(UInt(MemPath.busIdWidth))

        // `resetN` is active low, so ready is gated on it *high*: a slave refuses traffic while it is being reset. The
        // slot is elastic, exactly as the instruction bridge's: one request per cycle at steady state.
        io.bus.req.ready := (!respValid | io.bus.resp.ready) & io.resetN.asBool
        val fire = io.bus.req.valid & io.bus.req.ready
        val isRead = !io.bus.req.bits.wen
        val rdata = dpiUnclockedCall("bus_read", UInt(width), Some(Seq("addr")), Some("rdata"))(
            fire & isRead,
            io.bus.req.bits.addr
        )
        dpiClockedCall("bus_write", Some(Seq("addr", "wdata", "wstrb")))(
            io.clock,
            fire & io.bus.req.bits.wen,
            io.bus.req.bits.addr,
            io.bus.req.bits.wdata,
            io.bus.req.bits.wstrb.asBits.pad(32)
        )

        // A fill wins a same-cycle handover: the taken response leaves with the core, the accepted one takes its place.
        when(fire) {
            respValid := true.B
            respData := isRead ? (rdata, 0.U(width))
            respUser := io.bus.req.bits.user
            respId := io.bus.req.bits.id
        }.otherwise {
            when(respValid & io.bus.resp.ready) {
                respValid := false.B
            }
        }

        io.bus.resp.valid := respValid
        io.bus.resp.bits.data := respData
        io.bus.resp.bits.user := respUser
        io.bus.resp.bits.id := respId
