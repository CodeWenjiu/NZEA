package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.CoreBusReadOnly

/** IFU stage output. */
class IFUOut(width: Int) extends Bundle {
  val pc   = UInt(width.W)
  val inst = UInt(32.W)
}

/** Instruction Fetch Unit: holds PC, issues read requests, PC += 4 on readResp.fire.
  * Bus type and widths come from busGen (e.g. provided by Core).
  */
class IFU(busGen: () => CoreBusReadOnly, defaultPc: Long) extends Module {
  private val busProto = busGen()
  private val addrWidth = busProto.addrWidth
  private val dataWidth = busProto.dataWidth
  private val pcReset   = (defaultPc.toLong & ((1L << addrWidth) - 1)).U(addrWidth.W)

  val io = IO(new Bundle {
    val bus          = busGen()
    val out          = Decoupled(new IFUOut(addrWidth))
    val pc_redirect  = Input(Valid(UInt(addrWidth.W)))
  })
  val pc = RegInit(pcReset)

  io.bus.req.valid := io.out.ready
  io.bus.req.bits  := pc

  when(io.pc_redirect.valid) { pc := io.pc_redirect.bits }
    .elsewhen(io.bus.resp.fire) { pc := pc + 4.U }

  io.out.valid      := io.bus.resp.valid
  io.out.bits.pc    := pc
  io.out.bits.inst  := io.bus.resp.bits
  io.bus.resp.ready := io.out.ready
}
