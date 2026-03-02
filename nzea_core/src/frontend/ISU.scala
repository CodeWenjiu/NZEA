package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, MuxLookup, Valid}
import nzea_core.PipeIO
import nzea_config.NzeaConfig
import nzea_core.backend.fu.{AluInput, AluOp, AguInput, BruInput, BruOp, LsuOp, SysuInput}
import nzea_core.backend.{RobEnqPayload, GprBypass}

/** ISU: route by fu_type; ALU/BRU/LSU/SYSU; on dispatch, enqueues Rob entry (fu_type, rd_index). */
class ISU(addrWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val robDepth   = config.robDepth
  private val robIdWidth = chisel3.util.log2Ceil(robDepth.max(2))

  val io = IO(new Bundle {
    val in             = Flipped(new PipeIO(new IDUOut(addrWidth)))
    val rob_pending_rd = Input(UInt(32.W))
    val gpr_bypass     = Input(Valid(new GprBypass))
    val rob_enq        = Decoupled(new RobEnqPayload)
    val rob_enq_rob_id = Input(UInt(robIdWidth.W))
    val alu            = new PipeIO(new AluInput(robIdWidth))
    val bru            = new PipeIO(new BruInput(robIdWidth))
    val agu            = new PipeIO(new AguInput(robIdWidth))
    val sysu           = new PipeIO(new SysuInput(robIdWidth))
  })

  // Flush propagates from output (is2ex) to input (id2is)
  io.in.flush := io.alu.flush

  val fu_type = io.in.bits.fu_type
  val fu_src  = io.in.bits.fu_src
  val outs    = Seq(io.alu, io.bru, io.agu, io.sysu)
  val fuTypes = Seq(FuType.ALU, FuType.BRU, FuType.LSU, FuType.SYSU)

  val rs1       = io.in.bits.rs1
  val rs2       = io.in.bits.rs2
  val rs1_index = io.in.bits.rs1_index
  val rs2_index = io.in.bits.rs2_index
  val imm       = io.in.bits.imm
  val pc        = io.in.bits.pc
  val rob_id    = io.rob_enq_rob_id

  val can_bypass_rs1 = io.gpr_bypass.valid && rs1_index =/= 0.U && io.gpr_bypass.bits.rd === rs1_index
  val can_bypass_rs2 = io.gpr_bypass.valid && rs2_index =/= 0.U && io.gpr_bypass.bits.rd === rs2_index
  val rs1_val = Mux(can_bypass_rs1, io.gpr_bypass.bits.data, rs1)
  val rs2_val = Mux(can_bypass_rs2, io.gpr_bypass.bits.data, rs2)

  val conflict_rs1 = rs1_index =/= 0.U && io.rob_pending_rd(rs1_index)
  val conflict_rs2 = rs2_index =/= 0.U && io.rob_pending_rd(rs2_index)
  val stall = io.in.valid && ((conflict_rs1 && !can_bypass_rs1) || (conflict_rs2 && !can_bypass_rs2))

  // ALU path: FuDecode.take slices by enum width; no manual bit-width when AluSrc/AluOp change
  val (aluSrc, _) = AluSrc.safe(FuDecode.take(fu_src, AluSrc.getWidth))
  val aluOpA = MuxLookup(aluSrc.asUInt, rs1_val)(Seq(
    AluSrc.ImmZero.asUInt -> imm,
    AluSrc.PcImm.asUInt   -> pc
  ))
  val aluOpB = MuxLookup(aluSrc.asUInt, rs2_val)(Seq(
    AluSrc.Rs1Imm.asUInt  -> imm,
    AluSrc.ImmZero.asUInt -> 0.U(32.W),
    AluSrc.PcImm.asUInt   -> imm
  ))
  val aluOp = AluOp.safe(FuDecode.take(io.in.bits.fu_op, AluOp.getWidth))._1

  val can_dispatch = io.in.valid && !stall
  io.alu.valid := can_dispatch && (fu_type === FuType.ALU)
  io.alu.bits.opA   := aluOpA
  io.alu.bits.opB   := aluOpB
  io.alu.bits.aluOp := aluOp
  io.alu.bits.pc    := pc
  io.alu.bits.rob_id := rob_id

  // BRU path: pass pc, offset; next_pc from BRU output
  val (bruSrc, _)   = BruSrc.safe(FuDecode.take(fu_src, BruSrc.getWidth))
  val (bruOp, _)    = BruOp.safe(FuDecode.take(io.in.bits.fu_op, BruOp.getWidth))

  io.bru.valid := can_dispatch && (fu_type === FuType.BRU)
  io.bru.bits.pc           := pc
  io.bru.bits.pred_next_pc := io.in.bits.pred_next_pc
  io.bru.bits.offset       := imm
  io.bru.bits.use_rs1_imm := (bruSrc === BruSrc.Rs1Imm)
  io.bru.bits.rs1         := rs1_val
  io.bru.bits.rs2         := rs2_val
  io.bru.bits.bruOp       := bruOp
  io.bru.bits.rob_id      := rob_id

  // AGU path: next_pc from ROB head in WBU
  val (lsuOp, _)   = LsuOp.safe(FuDecode.take(io.in.bits.fu_op, LsuOp.getWidth))
  io.agu.valid := can_dispatch && (fu_type === FuType.LSU)
  io.agu.bits.base         := rs1_val
  io.agu.bits.imm          := imm
  io.agu.bits.lsuOp        := lsuOp
  io.agu.bits.storeData    := rs2_val
  io.agu.bits.rob_id       := rob_id
  io.agu.bits.pred_next_pc := io.in.bits.pred_next_pc

  io.sysu.valid := can_dispatch && (fu_type === FuType.SYSU)
  io.sysu.bits.rob_id := rob_id

  io.rob_enq.valid := can_dispatch
  io.rob_enq.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)
  io.rob_enq.bits.pred_next_pc := io.in.bits.pred_next_pc

  io.in.ready := !stall && io.rob_enq.ready && Mux1H(fuTypes.map(_ === fu_type), outs.map(_.ready))
}
