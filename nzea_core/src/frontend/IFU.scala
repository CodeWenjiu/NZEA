package nzea_core.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled, Valid}
import nzea_rtl.PipeIO
import nzea_core.frontend.bp.{BTB, BpUpdate, PHT, RAS, RasUpdate}
import nzea_rtl.LiteBusRW
import nzea_config.core.CoreConfig

/** Ibus user field layout: {pred_next_pc, pc, epoch}. epoch tags every request; on redirect it increments. Responses
  * with a stale epoch are drained — Rocket‑Chip / XiangShan style.
  */
object IbusUser {
  val epochBits = 4

  def userWidth(addrWidth: Int): Int = addrWidth * 2 + epochBits

  def pack(addrWidth: Int, pred_next_pc: UInt, pc: UInt, epoch: UInt): UInt =
    Cat(pred_next_pc, pc, epoch)

  def epoch(addrWidth: Int, user: UInt): UInt = user(epochBits - 1, 0)

  def pc(addrWidth: Int, user: UInt): UInt =
    user(addrWidth + epochBits - 1, epochBits)

  def predNextPc(addrWidth: Int, user: UInt): UInt =
    user(addrWidth * 2 + epochBits - 1, addrWidth + epochBits)

}

/** IFU stage output. */
class IFUOut(width: Int) extends Bundle {
  val pc = UInt(width.W)
  val inst = UInt(32.W)
  val pred_next_pc = UInt(width.W)
}

/** Instruction Fetch Unit: holds PC, issues read requests, PC += 4 on readResp.fire. */
class IFU(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val userWidth = IbusUser.userWidth(addrWidth)
  private val idWidth = 1
  private val busType = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)

  private val pcReset =
    (config.defaultPc & ((1L << addrWidth) - 1)).U(addrWidth.W)

  val io = IO(new Bundle {
    val bus = busType.cloneType
    val out = new PipeIO(new IFUOut(addrWidth))
    val redirect_pc = Input(UInt(addrWidth.W))
    val bp_update = Input(Valid(new BpUpdate))
    val ras_update = Input(Valid(new RasUpdate))
  })

  val pc = RegInit(pcReset)
  val pht = Module(new PHT(config.bpu.phtSize))
  val btb = Module(new BTB(config.bpu.btbSize))

  // RAS (optional): push from the execution side (a call that reaches the BRU
  // is a real call — no speculative pollution, and push happens early enough
  // for the ret fetch to read it); pop from the commit side (a ret commits
  // exactly once, so a mispredicted ret that re-executes cannot double-pop).
  private val ras = config.bpu.rasDepth.map { d =>
    val r = Module(new RAS(d))
    r.io.push := io.bp_update.valid && io.bp_update.bits.is_call.getOrElse(false.B)
    r.io.push_data := io.bp_update.bits.pc + 4.U
    r.io.pop := io.ras_update.valid && io.ras_update.bits.is_ret
    r
  }

  private val rasTop = ras.map(_.io.top).getOrElse(0.U(32.W))

  // ── RAS redirect ──
  // The response carries the inst, so a ret is recognized here (one cache
  // round-trip after its request). Override the carried pred_next_pc with the
  // RAS top and redirect fetch to it next cycle (epoch++ drains the wrongly
  // fetched sequential instructions in flight). When the RAS top matches the
  // real return address, the ret no longer mispredicts at the BRU.
  //
  // The epoch bump / pc redirect must be gated on the ret actually firing
  // downstream (`io.out.fire`): if the ret is stalled at the IFU output
  // (`out.valid && !out.ready`), bumping the epoch would invalidate the ret's
  // own response (`epochMatch` -> 0) and drop it from the stream — the ret is
  // never dispatched, yet the redirected target instructions get committed
  // ahead of it (observed in core-target difftest: ret at 0x800109b0 dropped,
  // next commit skipped to the return-target lw). While stalled, hold the ret
  // valid until it fires, then redirect.
  val instIsRet = io.out.valid && io.out.bits.inst(6, 0) === 0x67.U(7.W) &&
    io.out.bits.inst(19, 15) === 1.U && io.out.bits.inst(11, 7) =/= 1.U
  val rasRedirect = ras.map(r => instIsRet && r.io.top_valid).getOrElse(false.B)
  val rasRedirectFire = io.out.fire && rasRedirect

  // ── Epoch counter ──
  private val epoch = RegInit(0.U(IbusUser.epochBits.W))
  when(io.out.flush || rasRedirectFire) { epoch := epoch + 1.U }

  val pc_update = io.bus.req.fire

  // Fetch-start latch: enables the prediction mux only after the first fetch
  // fires — before that, PHT/BTB read outputs are meaningless.
  val started = RegInit(false.B)
  when(pc_update) { started := true.B }

  val pred_next_pc = Mux(
    RegNext(io.out.flush, false.B) || RegNext(rasRedirectFire, false.B),
    pc + 4.U,
    Mux(started && pht.io.pred_taken && btb.io.pred_hit, btb.io.pred_target, pc + 4.U)
  )

  // Prediction read address: after a fetch fires, predict the next fetch
  // address; while a request is stalled (fire did not happen), keep reading
  // the current pc so the prediction stays valid instead of drifting to pc+4.
  // Without this, any single stalled cycle discards the prediction, and the
  // fetch falls back to sequential (pc+4) — mispredicting every taken branch
  // whenever the cache accepts requests only every other cycle.
  val predAddr = Mux(pc_update, pred_next_pc, pc)

  pht.io.pc := predAddr
  pht.io.update := io.bp_update.valid
  pht.io.update_pc := io.bp_update.bits.pc
  pht.io.update_taken := io.bp_update.bits.taken

  btb.io.read_addr := predAddr
  btb.io.pc_for_tag := pc
  btb.io.update := io.bp_update.valid && io.bp_update.bits.taken
  btb.io.update_pc := io.bp_update.bits.pc
  btb.io.update_target := io.bp_update.bits.target

  io.bus.req.valid := io.out.ready && !reset.asBool
  io.bus.req.bits.addr := pc
  io.bus.req.bits.user := IbusUser.pack(addrWidth, pred_next_pc, pc, epoch)
  io.bus.req.bits.wdata := 0.U
  io.bus.req.bits.wen := false.B
  io.bus.req.bits.wstrb := 0.U
  io.bus.req.bits.id := 0.U

  when(io.out.flush) { pc := io.redirect_pc }
    .elsewhen(rasRedirectFire) { pc := rasTop }
    .elsewhen(pc_update) { pc := pred_next_pc }

  io.bus.resp.flush := io.out.flush

  private val respEpoch = IbusUser.epoch(addrWidth, io.bus.resp.bits.user)
  private val epochMatch = respEpoch === epoch

  io.out.bits.pc := IbusUser.pc(addrWidth, io.bus.resp.bits.user)

  io.out.bits.pred_next_pc := Mux(
    rasRedirect,
    rasTop,
    IbusUser.predNextPc(addrWidth, io.bus.resp.bits.user)
  )

  io.out.bits.inst := io.bus.resp.bits.data
  io.out.valid := io.bus.resp.valid && !io.out.flush && epochMatch
  io.bus.resp.ready := io.out.ready || io.out.flush || !epochMatch
}
