package nzea_core.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled, Valid}
import nzea_rtl.PipeIO
import nzea_core.frontend.bp.{BTB, BpUpdate, PHT}
import nzea_rtl.LiteBusRW
import nzea_core.config.CoreConfig

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
  })

  val pc = RegInit(pcReset)
  val pht = Module(new PHT(config.phtSize))
  val btb = Module(new BTB(config.btbSize))

  // ── Epoch counter ──
  private val epoch = RegInit(0.U(IbusUser.epochBits.W))
  when(io.out.flush) { epoch := epoch + 1.U }

  val pc_update = io.bus.req.fire

  val pred_next_pc = Mux(
    RegNext(io.out.flush, false.B),
    pc + 4.U,
    Mux(RegNext(pc_update, false.B) && pht.io.pred_taken && btb.io.pred_hit, btb.io.pred_target, pc + 4.U)
  )

  pht.io.pc := pred_next_pc
  pht.io.update := io.bp_update.valid
  pht.io.update_pc := io.bp_update.bits.pc
  pht.io.update_taken := io.bp_update.bits.taken

  btb.io.read_addr := pred_next_pc
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
