package nzea_device.clint

import chisel3._
import chisel3.util.{is, switch, Cat}
import nzea_rtl.LiteBusRW

/** RISC-V CLINT: mtime counter + mtimecmp with timer interrupt. Memory map (relative to base = 0x0200_0000): 0x0000
  * msip (W/R, 32-bit) 0x4000 mtimecmp_lo (R/W, 32-bit) 0x4004 mtimecmp_hi (R/W, 32-bit) 0xBFF8 mtime_lo (R, 32-bit)
  * 0xBFFC mtime_hi (R, 32-bit)
  */
class Clint(base: BigInt, userWidth: Int, idWidth: Int) extends Module {

  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(addrWidth = 32, dataWidth = 32, userWidth = userWidth, idWidth = idWidth))
    val timer_interrupt = Output(Bool())
  })

  val mtime = RegInit(0.U(64.W))
  mtime := mtime + 1.U

  val mtimecmp = RegInit(0.U(64.W))

  private val flush = io.bus.resp.flush
  private val busy = RegInit(false.B)
  private val respUser = RegInit(0.U(userWidth.W))
  private val respId = RegInit(0.U(idWidth.W))
  private val respData = RegInit(0.U(32.W))

  io.bus.req.ready := !busy && !flush
  io.bus.req.flush := false.B
  io.bus.resp.valid := busy && !flush
  io.bus.resp.bits.data := respData
  io.bus.resp.bits.user := respUser
  io.bus.resp.bits.id := respId

  private val localAddr = io.bus.req.bits.addr - base.U(32.W)
  private val reqFire = io.bus.req.valid && io.bus.req.ready

  when(reqFire) {
    respUser := io.bus.req.bits.user
    respId := io.bus.req.bits.id
    busy := true.B
    when(io.bus.req.bits.wen) {
      switch(localAddr) {
        is(0x4000.U) { mtimecmp := Cat(mtimecmp(63, 32), io.bus.req.bits.wdata) }
        is(0x4004.U) { mtimecmp := Cat(io.bus.req.bits.wdata, mtimecmp(31, 0)) }
      }
    }.otherwise {
      switch(localAddr) {
        is(0x0000.U) { respData := 0.U }
        is(0x4000.U) { respData := mtimecmp(31, 0) }
        is(0x4004.U) { respData := mtimecmp(63, 32) }
        is(0xbff8.U) { respData := mtime(31, 0) }
        is(0xbffc.U) { respData := mtime(63, 32) }
      }
    }
  }

  when(io.bus.resp.fire || flush) { busy := false.B }

  io.timer_interrupt := mtime >= mtimecmp && mtimecmp =/= 0.U
}
