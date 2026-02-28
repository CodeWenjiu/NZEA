package nzea_core.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled}
import nzea_core.CoreBusReadOnly
import nzea_config.NzeaConfig

/** IFU stage output. */
class IFUOut(width: Int) extends Bundle {
  val pc           = UInt(width.W)
  val inst         = UInt(32.W)
  val pred_next_pc = UInt(width.W)  // predicted (sequential pc+4)
}

/** Instruction Fetch Unit: holds PC, issues read requests, PC += 4 on readResp.fire.
  * Bus user = Cat(pred_next_pc, pc) for branch prediction / flush.
  */
class IFU(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val userWidth = addrWidth * 2  // pc + pred_next_pc
  private val busType   = new CoreBusReadOnly(addrWidth, dataWidth, userWidth)
  private val pcReset   = (config.defaultPc & ((1L << addrWidth) - 1)).U(addrWidth.W)

  val io = IO(new Bundle {
    val bus         = busType.cloneType
    val out         = Decoupled(new IFUOut(addrWidth))
    val flush       = Input(Bool())
    val redirect_pc = Input(UInt(addrWidth.W))
  })
  val pc = RegInit(pcReset)
  val pred_next_pc = pc + 4.U

  io.bus.req.valid := io.out.ready
  io.bus.req.bits.addr := pc
  io.bus.req.bits.user := Cat(pred_next_pc, pc)

  when(io.flush) { pc := io.redirect_pc }
    .elsewhen(io.bus.resp.fire) { pc := pc + 4.U }

  io.out.valid        := io.bus.resp.valid && !io.flush
  io.out.bits.pc      := io.bus.resp.bits.user(addrWidth - 1, 0)
  io.out.bits.pred_next_pc := io.bus.resp.bits.user(addrWidth * 2 - 1, addrWidth)
  io.out.bits.inst    := io.bus.resp.bits.data
  io.bus.resp.ready   := io.out.ready || io.flush
}
