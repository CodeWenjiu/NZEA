package nzea_core.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled, Valid}
import nzea_rtl.PipeIO
import nzea_core.frontend.bp.{BTB, BpUpdate, PHT}
import nzea_rtl.LiteBusRO
import nzea_core.config.CoreConfig

/** Ibus user field (addrWidth*2 bits): {pred_next_pc, pc[31:3], epoch}.
  *
  * Instructions are 4‑byte aligned so pc[2:0] is always 0b000 — those 3 bits carry the epoch tag. Rocket‑Chip /
  * XiangShan style: on redirect epoch increments; responses with a stale epoch are drained.
  */
object IbusUser {
  val epochBits = 2
  val epochMask = (1 << epochBits) - 1

  def pack(addrWidth: Int, pred_next_pc: UInt, pc: UInt, epoch: UInt): UInt = {
    val pcHi = pc(addrWidth - 1, epochBits)
    Cat(pred_next_pc, pcHi, epoch)
  }

  def epoch(addrWidth: Int, user: UInt): UInt = user(epochBits - 1, 0)

  def pc(addrWidth: Int, user: UInt): UInt = {
    val pcHi = user(addrWidth - 1, epochBits)
    Cat(pcHi, 0.U(epochBits.W))
  }

  def predNextPc(addrWidth: Int, user: UInt): UInt =
    user(addrWidth * 2 - 1, addrWidth)

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
  private val userWidth = addrWidth * 2
  private val busType = new LiteBusRO(addrWidth, dataWidth, userWidth)

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
