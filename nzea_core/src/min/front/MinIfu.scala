package nzea_core.min.front

import chisel3._
import nzea_rtl.{LiteBusRW, PipeIO}
import nzea_core.frontend.{IFUOut, IbusUser}
import nzea_config.core.CoreConfig

/** Minimal in-order fetcher: sequential `pc+4` only (always-not-taken), no BPU.
  *
  * LiteBus req/resp with an epoch tag: a redirect bumps the epoch so stale in-flight responses are discarded. A
  * downstream stall backpressures the bus resp (`resp.ready` follows `out.ready`), holding the current instruction.
  */
class MinIfu(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width
  private val userWidth = IbusUser.userWidth(addrWidth)
  private val busType = new LiteBusRW(addrWidth, addrWidth, userWidth, 1)

  val io = IO(new Bundle {
    val bus = busType.cloneType
    val out = new PipeIO(new IFUOut(addrWidth))
    val redirect_pc = Input(UInt(addrWidth.W))
  })

  private val pcReset =
    (config.defaultPc & ((1L << addrWidth) - 1)).U(addrWidth.W)

  val pc = RegInit(pcReset)
  val epoch = RegInit(0.U(IbusUser.epochBits.W))

  val pred_next_pc = pc + 4.U
  val pc_update = io.bus.req.fire

  when(io.out.flush) { epoch := epoch + 1.U }

  io.bus.req.valid := io.out.ready && !reset.asBool
  io.bus.req.bits.addr := pc
  io.bus.req.bits.user := IbusUser.pack(addrWidth, pred_next_pc, pc, epoch)
  io.bus.req.bits.wdata := 0.U
  io.bus.req.bits.wen := false.B
  io.bus.req.bits.wstrb := 0.U
  io.bus.req.bits.id := 0.U

  when(io.out.flush) { pc := io.redirect_pc }
    .elsewhen(pc_update) { pc := pred_next_pc }

  io.bus.resp.flush := io.out.flush

  private val respEpoch = IbusUser.epoch(addrWidth, io.bus.resp.bits.user)
  private val epochMatch = respEpoch === epoch

  io.out.bits.pc := IbusUser.pc(addrWidth, io.bus.resp.bits.user)
  io.out.bits.pred_next_pc := IbusUser.predNextPc(addrWidth, io.bus.resp.bits.user)
  io.out.bits.inst := io.bus.resp.bits.data
  io.out.valid := io.bus.resp.valid && !io.out.flush && epochMatch
  io.bus.resp.ready := io.out.ready || io.out.flush || !epochMatch
}
