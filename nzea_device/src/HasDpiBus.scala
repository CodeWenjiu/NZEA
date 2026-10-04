package nzea_device

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{DeviceTiming, LiteBusRW, LiteBusTiming, LiteBusTimingParameter}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A bus device: it answers its read/write bus with its own hardware, or — in the bridge flow — with the simulator.
  *
  * The DPI answer is uniform across every device: forward the request to remu's `bus_read` / `bus_write`, which routes
  * by address. So the whole implementation lives here once, and a device only declares it mixes in [[HasDpiBus]] and
  * hands [[answerBus]] its own logic — the flow branch and the DPI forward are the trait's, not the device's.
  *
  * The bridge answer carries the device's [[timing]]: the simulator answers in a cycle, and the timing sits between it
  * and the port, so what a master sees is the device's simulated speed, not the answer's. A synthesis flow never reads
  * the timing — it builds the device's hardware, whose speed is the macro's or the board's.
  *
  * `answerBus` is the device's entry point: in the bridge flow it drives `bus` from the simulator, otherwise it runs
  * `hardware`. A device whose hardware-only outputs would be undriven in the bridge flow ties them off itself.
  *
  * A device with more than one hardware implementation writes its own `flow match` and calls [[bridgeAnswer]] for the
  * bridge case: the rule stays here, once, and the device's match is the extension point.
  */
trait HasDpiBus:

    /** This device's timing in the simulator. Every bus device states one — the bridge answer is where it is applied,
      * so a device cannot quietly answer without it.
      */
    def timing: DeviceTiming

    /** Answer `bus`: the simulator in the bridge flow, the device's own `hardware` otherwise. */
    def answerBus(
        flow: RtlFlow,
        bus: Referable[LiteBusRW],
        resetN: Referable[Bool],
        addrWidth: Int,
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
        if flow.bridges then bridgeAnswer(bus, resetN, addrWidth, dataWidth, userWidth, idWidth)
        else hardware

    /** The simulator's answer, behind the device's timing: the forward below is one cycle, and [[LiteBusTiming]] wraps
      * it, so a master sees the device's latency and jitter instead.
      *
      * The port and the timing module's `in` are both slave views of the same bus, so they are wired by direction here;
      * `raw` is the plain wire the forward drives, in the slave convention the rest of this method reads.
      */
    def bridgeAnswer(
        bus: Referable[LiteBusRW],
        resetN: Referable[Bool],
        addrWidth: Int,
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
        val raw = Wire(new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth))
        val timed = LiteBusTiming.instantiate(
            LiteBusTimingParameter(addrWidth, dataWidth, userWidth, idWidth, timing)
        )
        timed.io.clock := summon[ClockScope].clock
        timed.io.resetN := resetN.asReset

        timed.io.in.req.valid := bus.req.valid
        timed.io.in.req.bits :<= bus.req.bits
        bus.req.ready := timed.io.in.req.ready
        bus.resp.valid := timed.io.in.resp.valid
        bus.resp.bits :<= timed.io.in.resp.bits
        timed.io.in.resp.ready := bus.resp.ready

        raw.req.valid := timed.io.out.req.valid
        raw.req.bits :<= timed.io.out.req.bits
        timed.io.out.req.ready := raw.req.ready
        timed.io.out.resp.valid := raw.resp.valid
        timed.io.out.resp.bits :<= raw.resp.bits
        raw.resp.ready := timed.io.out.resp.ready

        val respValid = RegInit(false.B)
        val respData = Reg(UInt(dataWidth))
        val respUser = Reg(UInt(userWidth))
        val respId = Reg(UInt(idWidth))

        // `resetN` is active low, so ready is gated on it *high*: a device refuses traffic while it is being reset. The
        // slot is elastic — taken-and-refilled in the same cycle is a handover, keeping one request per cycle.
        raw.req.ready := (!respValid | raw.resp.ready) & resetN
        val fire = raw.req.valid & raw.req.ready
        val isRead = !raw.req.bits.wen
        val rdata = dpiUnclockedCall("bus_read", UInt(dataWidth), Some(Seq("addr")), Some("rdata"))(
            fire & isRead,
            raw.req.bits.addr
        )
        dpiClockedCall("bus_write", Some(Seq("addr", "wdata", "wstrb")))(
            summon[ClockScope].clock,
            fire & raw.req.bits.wen,
            raw.req.bits.addr,
            raw.req.bits.wdata,
            raw.req.bits.wstrb.asBits.pad(32)
        )

        when(fire) {
            respValid := true.B
            respData := isRead ? (rdata, 0.U(dataWidth))
            respUser := raw.req.bits.user
            respId := raw.req.bits.id
        }.otherwise {
            when(respValid & raw.resp.ready) {
                respValid := false.B
            }
        }

        raw.resp.valid := respValid
        raw.resp.bits.data := respData
        raw.resp.bits.user := respUser
        raw.resp.bits.id := respId
