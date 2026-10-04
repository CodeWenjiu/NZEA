package nzea_device

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.RtlFlow
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A bus device: it answers its read/write bus with its own hardware, or — in the bridge flow — with the simulator.
  *
  * The DPI answer is uniform across every device: forward the request to remu's `bus_read` / `bus_write`, which routes
  * by address. So the whole implementation lives here once, and a device only declares it mixes in [[HasDpiBus]] and
  * hands [[answerBus]] its own logic — the flow branch and the DPI forward are the trait's, not the device's.
  *
  * `answerBus` is the device's entry point: in the bridge flow it drives `bus` from the simulator, otherwise it runs
  * `hardware`. A device whose hardware-only outputs would be undriven in the bridge flow ties them off itself.
  *
  * A device with more than one hardware implementation writes its own `flow match` and calls [[bridgeAnswer]] for the
  * bridge case: the rule stays here, once, and the device's match is the extension point.
  */
trait HasDpiBus:

    /** Answer `bus`: the simulator in the bridge flow, the device's own `hardware` otherwise. */
    def answerBus(
        flow: RtlFlow,
        bus: Referable[LiteBusRW],
        resetN: Referable[Bool],
        dataWidth: Int,
        userWidth: Int,
        idWidth: Int
    )(hardware: => Unit)(using
        ClockScope,
        ResetScope,
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit =
        if flow.bridges then bridgeAnswer(bus, resetN, dataWidth, userWidth, idWidth)
        else hardware

    /** The simulator's answer: one outstanding request, an elastic one-slot response register.
      *
      * Uniform across devices, so [[answerBus]] is the entry a single-implementation device uses. A device that matches
      * on the flow itself calls this for the bridge case.
      */
    def bridgeAnswer(
        bus: Referable[LiteBusRW],
        resetN: Referable[Bool],
        dataWidth: Int,
        userWidth: Int,
        idWidth: Int
    )(using
        ClockScope,
        ResetScope,
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit =
        val respValid = RegInit(false.B)
        val respData = Reg(UInt(dataWidth))
        val respUser = Reg(UInt(userWidth))
        val respId = Reg(UInt(idWidth))

        // `resetN` is active low, so ready is gated on it *high*: a device refuses traffic while it is being reset. The
        // slot is elastic — taken-and-refilled in the same cycle is a handover, keeping one request per cycle.
        bus.req.ready := (!respValid | bus.resp.ready) & resetN
        val fire = bus.req.valid & bus.req.ready
        val isRead = !bus.req.bits.wen
        val rdata = dpiUnclockedCall("bus_read", UInt(dataWidth), Some(Seq("addr")), Some("rdata"))(
            fire & isRead,
            bus.req.bits.addr
        )
        dpiClockedCall("bus_write", Some(Seq("addr", "wdata", "wstrb")))(
            summon[ClockScope].clock,
            fire & bus.req.bits.wen,
            bus.req.bits.addr,
            bus.req.bits.wdata,
            bus.req.bits.wstrb.asBits.pad(32)
        )

        when(fire) {
            respValid := true.B
            respData := isRead ? (rdata, 0.U(dataWidth))
            respUser := bus.req.bits.user
            respId := bus.req.bits.id
        }.otherwise {
            when(respValid & bus.resp.ready) {
                respValid := false.B
            }
        }

        bus.resp.valid := respValid
        bus.resp.bits.data := respData
        bus.resp.bits.user := respUser
        bus.resp.bits.id := respId
