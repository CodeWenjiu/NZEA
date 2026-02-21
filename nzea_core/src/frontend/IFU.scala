package nzea_core.frontend

import chisel3._
import chisel3.util.Decoupled
import nzea_config.NzeaConfig

/** Instruction Fetch Unit: holds PC, issues read requests, PC += 4 on readResp.fire. */
class IFU(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width

  val io = IO(new Bundle {
    val bus = new nzea_core.CoreBusReadOnly(addrWidth, dataWidth)
    val inst = Decoupled(UInt(dataWidth.W))
  })

  val pc = RegInit(config.defaultPc.U(addrWidth.W))

  io.bus.req.valid := io.inst.ready
  io.bus.req.bits  := pc

  when(io.bus.resp.fire) { pc := pc + 4.U }

  io.inst.valid        := io.bus.resp.valid
  io.inst.bits         := io.bus.resp.bits
  io.bus.resp.ready    := io.inst.ready
}
