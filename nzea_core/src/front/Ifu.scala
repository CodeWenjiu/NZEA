package nzea_core.front

import chisel3._
import chisel3.util.Valid
import nzea_rtl.{LiteBusRO, PipeIO}
import nzea_core.front.{IFUOut, IbusUser}
import nzea_config.core.CoreConfig

/** Minimal in-order fetcher: sequential `pc+4` only (always-not-taken), no BPU.
  *
  * Redirect is an explicit event separate from the flush broadcast: `io.redirect.valid` steers pc and bumps the epoch;
  * the flush chain only cancels in-flight state (REG1 clear, IDU ignore). Both are coincident here, driven from one
  * source (branch resolution); a prediction redirect later is the superset `redirect.valid && !flush`.
  *
  * Epoch = generation of the fetch stream, invalidating in-flight responses on every stream switch. Every response is
  * accepted (digested) but only the current epoch passes the out.valid gate, so cancellation is a master-side contract
  * and no bus-side cooperation is needed.
  */
class Ifu(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width
  private val userWidth = IbusUser.userWidth(addrWidth)
  private val busType = new LiteBusRO(addrWidth, addrWidth, userWidth, 1)

  val io = IO(new Bundle {
    val bus = busType.cloneType
    val out = new PipeIO(new IFUOut(addrWidth))
    val redirect = Input(Valid(UInt(addrWidth.W)))
  })

  private val pcReset =
    (config.defaultPc & ((1L << addrWidth) - 1)).U(addrWidth.W)

  val pc = RegInit(pcReset)
  val epoch = RegInit(0.U(IbusUser.epochBits.W))

  val pred_next_pc = pc + 4.U
  val pc_update = io.bus.req.fire

  when(io.redirect.valid) {
    pc := io.redirect.bits
  }.elsewhen(pc_update) { pc := pred_next_pc }
  when(io.redirect.valid) { epoch := epoch + 1.U }

  io.bus.req.valid := io.out.ready
  io.bus.req.bits.addr := pc
  io.bus.req.bits.user := IbusUser.pack(addrWidth, 0.U(addrWidth.W), pc, epoch)
  io.bus.req.bits.id := 0.U

  private val respEpoch = IbusUser.epoch(addrWidth, io.bus.resp.bits.user)
  private val epochMatch = respEpoch === epoch

  io.bus.resp.ready := io.out.ready

  io.out.bits.pc := IbusUser.pc(addrWidth, io.bus.resp.bits.user)
  io.out.bits.pred_next_pc := 0.U
  io.out.bits.inst := io.bus.resp.bits.data
  io.out.valid := io.bus.resp.valid && !io.out.flush && epochMatch
}