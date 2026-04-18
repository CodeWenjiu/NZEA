package nzea_rtl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim

import scala.sys.process.Process

trait LiteBusTestHelpers { this: ChiselSim =>
  protected lazy val hasVerilator: Boolean =
    Process(Seq("bash", "-lc", "command -v verilator >/dev/null 2>&1")).! == 0

  protected def initMaster(bus: LiteBusRW, respReady: Boolean = true): Unit = {
    bus.req.valid.poke(false.B)
    bus.req.bits.addr.poke(0.U)
    bus.req.bits.wdata.poke(0.U)
    bus.req.bits.wen.poke(false.B)
    bus.req.bits.wstrb.poke(0.U)
    bus.req.bits.user.poke(0.U)
    bus.resp.ready.poke(if (respReady) true.B else false.B)
  }

  protected def initEndpoint(bus: LiteBusRW, reqReady: Boolean, respValid: Boolean = false): Unit = {
    bus.req.ready.poke(if (reqReady) true.B else false.B)
    bus.req.flush.poke(false.B)
    bus.resp.valid.poke(if (respValid) true.B else false.B)
    bus.resp.bits.data.poke(0.U)
    bus.resp.bits.user.poke(0.U)
  }

  protected def driveReq(
    bus: LiteBusRW,
    addr: BigInt,
    user: BigInt,
    wen: Boolean = false,
    wdata: BigInt = 0,
    wstrb: BigInt = 0xf
  ): Unit = {
    bus.req.valid.poke(true.B)
    bus.req.bits.addr.poke(addr.U)
    bus.req.bits.user.poke(user.U)
    bus.req.bits.wen.poke(if (wen) true.B else false.B)
    bus.req.bits.wdata.poke(wdata.U)
    bus.req.bits.wstrb.poke(wstrb.U)
  }

  protected def clearReq(bus: LiteBusRW): Unit = bus.req.valid.poke(false.B)

  protected def driveResp(bus: LiteBusRW, data: BigInt, user: BigInt): Unit = {
    bus.resp.valid.poke(true.B)
    bus.resp.bits.data.poke(data.U)
    bus.resp.bits.user.poke(user.U)
  }

  protected def clearResp(bus: LiteBusRW): Unit = bus.resp.valid.poke(false.B)
}
