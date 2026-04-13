package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_rtl.PipeIO
import nzea_core.frontend.bp.{PHT, BTB, BpUpdate}
import nzea_rtl.CoreBusReadOnly
import nzea_config.CoreConfig

/** Ibus user payload: pred_next_pc + pc, passthrough req->resp for branch
  * prediction / flush.
  */
class IbusUserBundle(width: Int) extends Bundle {
  val pred_next_pc = UInt(width.W)
  val pc = UInt(width.W)
}

/** IFU stage output. */
class IFUOut(width: Int) extends Bundle {
  val pc = UInt(width.W)
  val inst = UInt(32.W)
  val pred_next_pc = UInt(width.W) // predicted (sequential pc+4)
}

/** Instruction Fetch Unit: holds PC, issues read requests, PC += 4 on
  * readResp.fire.
  */
class IFU(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val userBundleType = new IbusUserBundle(addrWidth)
  private val userWidth = addrWidth * 2
  private val busType = new CoreBusReadOnly(addrWidth, dataWidth, userWidth)
  private val pcReset =
    (config.defaultPc & ((1L << addrWidth) - 1)).U(addrWidth.W)

  val io = IO(new Bundle {
    val bus = busType.cloneType
    val out = new PipeIO(new IFUOut(addrWidth))
    val redirect_pc = Input(UInt(addrWidth.W))
    val bp_update   = Input(Valid(new BpUpdate))
  })

  val pc = RegInit(pcReset)
  val pht = Module(new PHT(config.phtSize))
  val btb = Module(new BTB(config.btbSize))

  val pc_update = io.bus.req.fire

  val pred_next_pc = Mux(RegNext(io.out.flush, false.B), pc + 4.U,
    Mux(RegNext(pc_update) && pht.io.pred_taken && btb.io.pred_hit, btb.io.pred_target, pc + 4.U))
  // SyncReadMem has 1-cycle read latency: we must pass pred_next_pc so the result
  // aligns with the fetched PC next cycle.
  pht.io.pc := pred_next_pc
  pht.io.update := io.bp_update.valid
  pht.io.update_pc := io.bp_update.bits.pc
  pht.io.update_taken := io.bp_update.bits.taken

  btb.io.read_addr  := pred_next_pc
  btb.io.pc_for_tag := pc
  btb.io.update := io.bp_update.valid && io.bp_update.bits.taken
  btb.io.update_pc := io.bp_update.bits.pc
  btb.io.update_target := io.bp_update.bits.target

  io.bus.req.valid := io.out.ready
  io.bus.req.bits.addr := pc
  val userReq = Wire(userBundleType)
  userReq.pred_next_pc := pred_next_pc
  userReq.pc := pc
  io.bus.req.bits.user := userReq.asUInt

  when(io.out.flush) { pc := io.redirect_pc }
    .elsewhen(pc_update) { pc := pred_next_pc }

  io.out.valid := io.bus.resp.valid && !io.out.flush
  val userResp = io.bus.resp.bits.user.asTypeOf(userBundleType)
  io.out.bits.pc := userResp.pc
  io.out.bits.pred_next_pc := userResp.pred_next_pc
  io.out.bits.inst := io.bus.resp.bits.data
  io.bus.resp.ready := io.out.ready || io.out.flush
  io.bus.resp.flush := io.out.flush
}
