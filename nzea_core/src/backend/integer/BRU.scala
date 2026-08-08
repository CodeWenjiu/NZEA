package nzea_core.backend.integer

import chisel3._
import chisel3.util.{Mux1H, Valid}
import nzea_rtl.{PipeIO, PipelineConnect, StatsRegs}
import nzea_config.core.CoreConfig
import nzea_config.core.PayloadSpec
import nzea_core.frontend.PrfWriteBundle
import nzea_core.frontend.bp.BpUpdate
import nzea_core.retire.rob.Rob

/** BRU op: one-hot (JAL, JALR, BEQ, BNE, BLT, BGE, BLTU, BGEU). */
object BruOp extends chisel3.ChiselEnum {
  val JAL = Value((1 << 0).U)
  val JALR = Value((1 << 1).U)
  val BEQ = Value((1 << 2).U)
  val BNE = Value((1 << 3).U)
  val BLT = Value((1 << 4).U)
  val BGE = Value((1 << 5).U)
  val BLTU = Value((1 << 6).U)
  val BGEU = Value((1 << 7).U)
}

/** BRU input: pc, pred_next_pc, offset (imm), rs1/rs2 for branch compare, bruOp; rob_id, p_rd from IS.
  * `rd_index`/`is_ret` are RAS-driven information units (see PayloadSpec): they vanish when RAS is disabled and no sim
  * ret-statistics are requested.
  */
class BruInput(robIdWidth: Int, prfAddrWidth: Int)(implicit config: CoreConfig) extends Bundle {
  val pc = UInt(32.W)
  val pred_next_pc = UInt(32.W)
  val offset = UInt(32.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
  val bruOp = BruOp()
  val rob_id = UInt(robIdWidth.W)
  val p_rd = UInt(prfAddrWidth.W)
  val rd_index = if (PayloadSpec.enabled(PayloadSpec.CallExec)) Some(UInt(5.W)) else None
  val is_ret = if (PayloadSpec.enabled(PayloadSpec.RetExec)) Some(Bool()) else None
}

/** BRU stage-1 payload: resolved next_pc, flush (mispredict), is_taken; pc_plus_4 for JAL/JALR; for ROB and IFU.
  * `is_call` exists iff RAS is enabled (PayloadSpec.CallExec).
  */
class BruS1Out(robIdWidth: Int, prfAddrWidth: Int)(implicit config: CoreConfig) extends Bundle {
  val rob_id = UInt(robIdWidth.W)
  val p_rd = UInt(prfAddrWidth.W)
  val pc = UInt(32.W)
  val next_pc = UInt(32.W)
  val pc_plus_4 = UInt(32.W)
  val flush = Bool()
  val is_taken = Bool()
  val is_call = if (PayloadSpec.enabled(PayloadSpec.CallExec)) Some(Bool()) else None
}

/** BRU Stage 0: computes target, is_taken, flush (mispredict). Outputs PipeIO(BruS1Out). */
class BRUStage0(robIdWidth: Int, prfAddrWidth: Int)(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new BruInput(robIdWidth, prfAddrWidth)))
    val out = new PipeIO(new BruS1Out(robIdWidth, prfAddrWidth))
  })

  val b = io.in.bits
  val bruOpU = b.bruOp.asUInt
  val is_jalr = bruOpU(1)
  val target = Mux(is_jalr, b.rs1 + b.offset, b.pc + b.offset)
  val is_jmp = bruOpU(0) || bruOpU(1)
  val eq = b.rs1 === b.rs2
  val ne = b.rs1 =/= b.rs2
  val lt = b.rs1.asSInt < b.rs2.asSInt
  val ge = b.rs1.asSInt >= b.rs2.asSInt
  val ltu = b.rs1 < b.rs2
  val geu = b.rs1 >= b.rs2

  val branchTaken = Mux1H(
    bruOpU,
    Seq(
      true.B,
      true.B,
      eq,
      ne,
      lt,
      ge,
      ltu,
      geu
    )
  )

  val is_taken = is_jmp || branchTaken
  val next_pc = Mux(is_taken, target, b.pc + 4.U)
  val mispredict = b.pred_next_pc =/= next_pc
  // Direction of the fetch-side prediction (BTB hit && PHT taken) vs actual outcome.
  // pred_taken implies the IFU redirected to the BTB target; dir_mispred splits
  // mispredict into direction errors; the remainder are target errors (JALR etc.).
  val pred_taken = b.pred_next_pc =/= b.pc + 4.U
  val dir_mispred = pred_taken =/= is_taken
  // ret classification for RAS effectiveness counters (carried from IDU decode).
  val isRetS0 = b.is_ret.getOrElse(false.B)

  // Simulation-only branch-prediction statistics. Counter state lives in a
  // StatsRegs black box whose stat_* regs carry /*verilator public_flat_rd*/
  // metacomments (VPI contract: stat_* leaf names globally unique). Counters
  // are execution-side: branches of squashed instructions are included.
  if (config.sim) {
    val stats = Module(
      new StatsRegs(
        "BpStats",
        Seq(
          "stat_bp_branch" -> 32,
          "stat_bp_mispred" -> 32,
          "stat_bp_pred_taken" -> 32,
          "stat_bp_actual_taken" -> 32,
          "stat_bp_dir_mispred" -> 32,
          "stat_bp_ret" -> 32,
          "stat_bp_ret_mispred" -> 32
        )
      )
    )
    stats.clock := clock
    stats.reset := reset
    stats.ports("stat_bp_branch").en := io.in.valid
    stats.ports("stat_bp_branch").data := stats.ports("stat_bp_branch").value + 1.U
    stats.ports("stat_bp_mispred").en := io.in.valid && mispredict
    stats.ports("stat_bp_mispred").data := stats.ports("stat_bp_mispred").value + 1.U
    stats.ports("stat_bp_pred_taken").en := io.in.valid && pred_taken
    stats.ports("stat_bp_pred_taken").data := stats.ports("stat_bp_pred_taken").value + 1.U
    stats.ports("stat_bp_actual_taken").en := io.in.valid && is_taken
    stats.ports("stat_bp_actual_taken").data := stats.ports("stat_bp_actual_taken").value + 1.U
    stats.ports("stat_bp_dir_mispred").en := io.in.valid && dir_mispred
    stats.ports("stat_bp_dir_mispred").data := stats.ports("stat_bp_dir_mispred").value + 1.U
    stats.ports("stat_bp_ret").en := io.in.valid && isRetS0
    stats.ports("stat_bp_ret").data := stats.ports("stat_bp_ret").value + 1.U
    stats.ports("stat_bp_ret_mispred").en := io.in.valid && isRetS0 && mispredict
    stats.ports("stat_bp_ret_mispred").data := stats.ports("stat_bp_ret_mispred").value + 1.U
  }

  io.out.valid := io.in.valid
  io.out.bits.rob_id := b.rob_id
  io.out.bits.p_rd := b.p_rd
  io.out.bits.pc := b.pc
  io.out.bits.next_pc := next_pc
  io.out.bits.pc_plus_4 := b.pc + 4.U
  io.out.bits.flush := mispredict
  io.out.bits.is_taken := is_taken
  // RAS push classification at the input stage: JAL/JALR linking to x1.
  // PIC-style calls encode as `jalr x1, x1, off` (jump-and-link through the
  // link register), so JALR is a call whenever rd==x1, regardless of rs1.
  val isJalr = BruOp.safe(b.bruOp.asUInt)._1 === BruOp.JALR
  val isJal = BruOp.safe(b.bruOp.asUInt)._1 === BruOp.JAL
  io.out.bits.is_call.foreach(_ := (isJal || isJalr) && b.rd_index.getOrElse(0.U) === 1.U)
  io.in.ready := io.out.ready
  io.in.flush := io.out.flush
}

/** BRU Stage 1: receives BruS1Out, outputs to ROB, PRF, IFU. */
class BRUStage1(robIdWidth: Int, prfAddrWidth: Int)(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new BruS1Out(robIdWidth, prfAddrWidth)))
    val flush = Input(Bool())
    val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
    val out = new nzea_rtl.PipeIO(new PrfWriteBundle(prfAddrWidth))
    val bp_update = Output(Valid(new BpUpdate))
  })

  val b = io.in.bits

  io.rob_access <> Rob.entryStateUpdate(
    io.in.valid,
    b.rob_id,
    is_done = true.B,
    flush = b.flush,
    next_pc = b.next_pc
  )(robIdWidth)

  io.out.valid := io.in.valid
  io.out.bits.addr := b.p_rd
  io.out.bits.data := b.pc_plus_4

  io.bp_update.valid := io.in.valid
  io.bp_update.bits.pc := b.pc
  io.bp_update.bits.taken := b.is_taken
  io.bp_update.bits.target := b.next_pc
  // RAS push on the execution side: a call that reaches the BRU is a real
  // call (wrong-path calls never get here), and push happens ~0 cycles after
  // resolution — early enough for the ret fetch to read it.
  io.bp_update.bits.is_call.foreach(_ := b.is_call.getOrElse(false.B))

  io.in.ready := io.out.ready
  io.in.flush := io.flush
}

/** BRU: 2-stage pipeline. S0 computes; S1 outputs. PipelineConnect internally. */
class BRU(robIdWidth: Int, prfAddrWidth: Int)(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new BruInput(robIdWidth, prfAddrWidth)))
    val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
    val out = new nzea_rtl.PipeIO(new PrfWriteBundle(prfAddrWidth))
    val bp_update = Output(Valid(new BpUpdate))
  })

  val s0 = Module(new BRUStage0(robIdWidth, prfAddrWidth))
  val s1 = Module(new BRUStage1(robIdWidth, prfAddrWidth))

  io.in <> s0.io.in
  io.in.flush := io.out.flush
  s1.io.flush := io.out.flush
  PipelineConnect(s0.io.out, s1.io.in)
  io.rob_access <> s1.io.rob_access
  io.out.valid := s1.io.out.valid
  io.out.bits := s1.io.out.bits
  s1.io.out.ready := io.out.ready
  s1.io.out.flush := io.out.flush
  io.bp_update := s1.io.bp_update
}
