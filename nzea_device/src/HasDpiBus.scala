package nzea_device

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{BusTiming, LiteBusRW, LiteBusTiming, LiteBusTimingParameter}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A bus device: it answers its read/write bus with its own hardware, or — in the bridge flow — with the simulator,
  * which is uniform across devices: forward the request to remu's `bus_read` / `bus_write`, which routes by address. A
  * port with a timing table gets the table's latency, jitter and held rows; a port with no table is the simulator's own
  * answer, one register deep — a device that states no cost of its own, which is what a register file at the bus's own
  * pace is.
  *
  * `answerBus` is the device's entry point; a device with more than one hardware implementation writes its own `flow
  * match` and calls the bridge branch it needs, and ties off its hardware-only outputs itself.
  */
trait HasDpiBus:

    /** The bridge answer, shaped by the port's timing table when it has one. */
    def answerBus(
        flow: RtlFlow,
        timing: Option[BusTiming],
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
            timing match
                case Some(table) =>
                    timedAnswer(
                        table,
                        clockHz,
                        bus,
                        resetN,
                        addrWidth,
                        dataWidth,
                        userWidth,
                        idWidth,
                        counters = flow.counters,
                        stats = StatScope.root
                    )
                case None => bridgeAnswer(bus, resetN, dataWidth, userWidth, idWidth)
        else hardware

    /** The simulator's answer with nothing in front of it: the request fires, the answer is registered, and the master
      * sees it one cycle later. That is the whole cost of a port that states no timing of its own.
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

    /** The simulator's answer behind the port's timing table: [[bridgeAnswer]] supplies the first cycle, and
      * [[LiteBusTiming]] adds the table's own stages, jitter and held rows. The port and the timing module's `in` are
      * both slave views of the same bus, so they are wired by direction here. `clockHz` is the bus clock the table's
      * [[nzea_rtl.bus.AccessCost]] resolves against.
      */
    def timedAnswer(
        timing: BusTiming,
        clockHz: Int,
        bus: Referable[LiteBusRW],
        resetN: Referable[Bool],
        addrWidth: Int,
        dataWidth: Int,
        userWidth: Int,
        idWidth: Int,
        counters: Boolean,
        stats: StatScope
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
            LiteBusTimingParameter(addrWidth, dataWidth, userWidth, idWidth, clockHz, timing, counters, stats)
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
