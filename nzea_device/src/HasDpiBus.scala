package nzea_device

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{Delay, LiteBusDelay, LiteBusDelayParameter, LiteBusRW}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The bridge flow's delay a device states for itself. The default is none: the simulator's own answer register is the
  * device's whole cost, which is what a register file at the bus's pace is. A device only the simulator stands in for
  * overrides it with its measured delay.
  */
trait HasBridgeDelay[P <: Parameter]:
    def bridgeDelay(parameter: P): Option[Delay] = None

/** A bus device: it answers its read/write bus with its own hardware, or — in the bridge flow — with the simulator,
  * which is uniform across devices: forward the request to remu's `bus_read` / `bus_write`, which routes by address.
  * The device's own [[HasBridgeDelay]] decides whether that answer is delayed; a device with no delay is the
  * simulator's own answer, one register deep — a register file at the bus's own pace.
  *
  * `answerBus` is the device's entry point when the simulator replaces it wholesale; a device with more than one
  * hardware implementation writes its own `flow match` and calls the bridge branch it needs, and ties off its
  * hardware-only outputs itself. A device whose RTL runs in both flows ([[nzea_device.devices.Uart]]) skips it and
  * branches on `flow` for the boundary alone.
  */
trait HasDpiBus:

    /** The bridge answer, delayed by the port's own fact when it has one. */
    def answerBus(
        flow: RtlFlow,
        delay: Option[Delay],
        clockHz: Int,
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
        if flow.bridges then
            delay match
                case Some(cost) =>
                    delayedAnswer(cost.at(clockHz), bus, resetN, addrWidth, dataWidth, userWidth, idWidth)
                case None => bridgeAnswer(bus, resetN, dataWidth, userWidth, idWidth)
        else hardware

    /** The simulator's answer with nothing in front of it: the request fires, the answer is registered, and the master
      * sees it one cycle later. That is the whole cost of a port that states no delay of its own.
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

    /** The simulator's answer behind the port's own delay: [[bridgeAnswer]] supplies the first cycle, and
      * [[LiteBusDelay]] adds the rest. The port and the delay module's `in` are both slave views of the same bus, so
      * they are wired by direction here.
      */
    def delayedAnswer(
        latency: Int,
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
        // The instance's name is the waveform's handle; without the shadow the hub names it `_GEN_2`.
        val timed =
            given sourcecode.Name.Machine = sourcecode.Name.Machine("delay")
            LiteBusDelay.instantiate(
                LiteBusDelayParameter(addrWidth, dataWidth, userWidth, idWidth, latency)
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

        bridgeAnswer(raw, resetN, dataWidth, userWidth, idWidth)
