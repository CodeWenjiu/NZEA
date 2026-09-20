package nzea_core.min.front

import chisel3._
import chisel3.util.Valid
import nzea_rtl.{LiteBusRO, PipeIO}
import nzea_core.frontend.{IFUOut, IbusUser}
import nzea_config.core.CoreConfig

/** Minimal in-order fetcher: sequential `pc+4` only (always-not-taken), no BPU.
  *
  * Redirect is an explicit event, separate from the flush broadcast: `io.redirect.valid` changes pc (the redirect
  * target), while the flush chain only cancels in-flight state (epoch bump, REG1 clear, IDU ignore). The in-order
  * engine drives both from the same source (branch resolution), so they are always coincident here; a future
  * prediction redirect (steer without flush) is the superset case `redirect.valid && !flush` and needs no change.
  *
  * Epoch tags on every request (a stream switch bumps the epoch): a branch flush cancels in-flight requests — the
  * fetch side must not *wait* for any response; and with pipelined requests, responses may return out of order, so
  * the fetch side must not *guess* which response is stale. Epoch matching is stateless: responses carrying a stale
  * epoch are accepted (digested) but never pass the out.valid gate. The counter must be wide enough for all
  * responses that can be in flight between two flushes (a 1-bit tag wraps and can admit a stale response after two
  * close flushes); the shared [[IbusUser]] epoch width satisfies that. Cancellation is a master-side contract here:
  * `resp.flush` is driven inert and the bus needs no device cooperation for the fetch side. A BPU and a prediction
  * chain come back later as configurable units, but the epoch discard is a correctness baseline and stays.
  */
class MinIfu(implicit config: CoreConfig) extends Module {
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
  // Epoch = generation of the fetch stream: bumps on every stream switch (redirect), which invalidates all
  // in-flight responses. A flush is always accompanied by a redirect — protocol-wise the two are coincident (the
  // in-order engine drives them from one source), and any future flush-like event (e.g. an external fetch cancel)
  // is expressed as a redirect to the same pc; a `flush && !redirect` would be a protocol violation and is left
  // visible (stale responses would then pass epoch matching) instead of being masked.
  when(io.redirect.valid) { epoch := epoch + 1.U }

  io.bus.req.valid := io.out.ready && !reset.asBool
  io.bus.req.bits.addr := pc
  // Shared user layout for width parity with the OoO IFU; the prediction field stays inert (no BPU yet).
  io.bus.req.bits.user := IbusUser.pack(addrWidth, 0.U(addrWidth.W), pc, epoch)
  io.bus.req.bits.id := 0.U

  private val respEpoch = IbusUser.epoch(addrWidth, io.bus.resp.bits.user)
  private val epochMatch = respEpoch === epoch

  io.bus.resp.ready := io.out.ready
  // No device-side cancellation on this engine: every response is accepted (digested) and only the out.valid gate
  // (flush/epoch) decides whether it reaches the pipeline — the bus contract for the fetch side is plain Decoupled,
  // cancellation happens entirely in the IFU. A stale response may hold the resp channel for a cycle or two while
  // REG1 is busy, then is digested on the next REG1-empty window (a flush always empties REG1).

  io.out.bits.pc := IbusUser.pc(addrWidth, io.bus.resp.bits.user)
  io.out.bits.pred_next_pc := 0.U // prediction chain removed from the fetch core
  io.out.bits.inst := io.bus.resp.bits.data
  io.out.valid := io.bus.resp.valid && !io.out.flush && epochMatch
}