package nzea_core.frontend

import chisel3._
import chisel3.util.Decoupled
import nzea_core.CoreBusReadOnly

/** Instruction Fetch Unit: holds PC, issues read requests, PC += 4 on readResp.fire.
  * Bus type and widths come from busGen (e.g. provided by Core).
  */
class IFU(busGen: () => CoreBusReadOnly, defaultPc: Long) extends Module {
  private val busProto = busGen()
  private val addrWidth = busProto.addrWidth
  private val dataWidth = busProto.dataWidth
  private val pcReset   = (defaultPc.toLong & ((1L << addrWidth) - 1)).U(addrWidth.W)

  val io = IO(new Bundle {
    val bus  = busGen()
    val inst = Decoupled(UInt(dataWidth.W))
  })
  val pc               = RegInit(pcReset)

  io.bus.req.valid := io.inst.ready
  io.bus.req.bits  := pc

  when(io.bus.resp.fire) { pc := pc + 4.U }

  io.inst.valid        := io.bus.resp.valid
  io.inst.bits         := io.bus.resp.bits
  io.bus.resp.ready    := io.inst.ready
}
