package nzea_core.min.front

import chisel3._
import nzea_rtl.{LiteBusRO, PipeIO}
import nzea_core.frontend.{IFUOut, IbusUser}
import nzea_config.core.CoreConfig

/** Minimal in-order fetcher: sequential `pc+4` only (always-not-taken), no BPU.
  *
  * Epoch tags on every request (`flush` bumps the epoch): a branch flush cancels in-flight requests — the bus `flush`
  * semantics let a device never return the cancelled response — so the fetch side must not *wait* for any response;
  * and with pipelined requests, responses may return out of order, so the fetch side must not *guess* which response
  * is stale. Epoch matching is stateless: responses carrying a stale epoch are dropped. The counter must be wide
  * enough for all responses that can be in flight between two flushes (a 1-bit tag wraps and can admit a stale
  * response after two close flushes); the shared [[IbusUser]] epoch width satisfies that. A BPU and a prediction chain
  * come back later as configurable units, but the epoch discard is a correctness baseline and stays.
  */
class MinIfu(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width
  private val userWidth = IbusUser.userWidth(addrWidth)
  private val busType = new LiteBusRO(addrWidth, addrWidth, userWidth, 1)

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

  when(io.out.flush) {
    epoch := epoch + 1.U
    pc := io.redirect_pc
  }.elsewhen(pc_update) { pc := pred_next_pc }

  io.bus.req.valid := io.out.ready && !reset.asBool
  io.bus.req.bits.addr := pc
  // Shared user layout for width parity with the OoO IFU; the prediction field stays inert (no BPU yet).
  io.bus.req.bits.user := IbusUser.pack(addrWidth, 0.U(addrWidth.W), pc, epoch)
  io.bus.req.bits.id := 0.U

  private val respEpoch = IbusUser.epoch(addrWidth, io.bus.resp.bits.user)
  private val epochMatch = respEpoch === epoch

  io.bus.resp.ready := io.out.ready || io.out.flush || !epochMatch
  io.bus.resp.flush := io.out.flush

  io.out.bits.pc := IbusUser.pc(addrWidth, io.bus.resp.bits.user)
  io.out.bits.pred_next_pc := 0.U // prediction chain removed from the fetch core
  io.out.bits.inst := io.bus.resp.bits.data
  io.out.valid := io.bus.resp.valid && !io.out.flush && epochMatch
}