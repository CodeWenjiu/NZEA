package nzea_device.finisher

import chisel3._
import chisel3.util._
import nzea_rtl.FabricBusRW

class SifiveTestFinisher(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val finished = Output(Bool())
  })

  private val flush       = io.bus.resp.flush
  private val finished    = RegInit(false.B)
  private val busCycle    = RegInit(0.U(2.W))
  private val respUser    = RegInit(0.U(userWidth.W))
  private val respId      = RegInit(0.U(idWidth.W))

  private val reqReady = busCycle === 0.U && !flush
  private val reqFire  = io.bus.req.valid && reqReady
  io.bus.req.ready := reqReady
  io.bus.req.flush := false.B
  io.bus.resp.valid      := busCycle === 3.U && !flush
  io.bus.resp.bits.data  := 0.U
  io.bus.resp.bits.user  := respUser
  io.bus.resp.bits.id    := respId
  io.finished := finished

  when(reqFire) {
    respUser := io.bus.req.bits.user; respId := io.bus.req.bits.id; busCycle := 1.U
    when(io.bus.req.bits.wen) { finished := true.B }
  }
  when(busCycle === 1.U) { busCycle := 2.U }
  when(busCycle === 2.U) { busCycle := 3.U }
  when((busCycle === 3.U && io.bus.resp.ready) || flush) { busCycle := 0.U }
}
