package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, MuxLookup, Valid}
import nzea_core.PipeIO
import nzea_config.NzeaConfig
import nzea_core.backend.{AluInput, AluOp, AguInput, BruInput, BruOp, LsuOp, SysuInput}
import nzea_core.retire.rob.{RobEnqIO, RobSlotRead, RobSlotReadPort, RobState}

/** ISU: route by fu_type; ALU/BRU/LSU/SYSU; on dispatch, enqueues Rob entry (fu_type, rd_index). */
class ISU(addrWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val robDepth   = config.robDepth
  private val robIdWidth = chisel3.util.log2Ceil(robDepth.max(2))

  val io = IO(new Bundle {
    val in             = Flipped(new PipeIO(new IDUOut(addrWidth, robIdWidth)))
    val rob_enq        = Flipped(new RobEnqIO(robIdWidth))
    val rob_slot_rs1   = Flipped(new RobSlotReadPort(robIdWidth))
    val rob_slot_rs2   = Flipped(new RobSlotReadPort(robIdWidth))
    val rat_write      = Output(Valid(new Bundle { val rd_index = UInt(5.W); val rob_id = UInt(robIdWidth.W) }))
    val alu            = new PipeIO(new AluInput(robIdWidth))
    val bru            = new PipeIO(new BruInput(robIdWidth))
    val agu            = new PipeIO(new AguInput(robIdWidth))
    val sysu           = new PipeIO(new SysuInput(robIdWidth))
  })

  val fu_type = io.in.bits.fu_type
  val fu_src  = io.in.bits.fu_src
  val outs    = Seq(io.alu, io.bru, io.agu, io.sysu)
  val fuTypes = Seq(FuType.ALU, FuType.BRU, FuType.LSU, FuType.SYSU)

  // Flush propagates from output (is2ex) to input (id2is)
  io.in.flush := outs.map(_.flush).reduce(_ || _)

  val rs1       = io.in.bits.rs1
  val rs2       = io.in.bits.rs2
  val rs1_rat   = io.in.bits.rs1_rat
  val rs2_rat   = io.in.bits.rs2_rat
  val imm       = io.in.bits.imm
  val pc        = io.in.bits.pc
  val rob_id    = io.rob_enq.rob_id

  io.rob_slot_rs1.rob_id := rs1_rat.rob_id
  io.rob_slot_rs2.rob_id := rs2_rat.rob_id
  val slot_rs1 = io.rob_slot_rs1.slot
  val slot_rs2 = io.rob_slot_rs2.slot

  def needStall(rat: RatEntry, slot: RobSlotRead): Bool = {
    rat.busy && slot.rob_state =/= RobState.Done
  }

  val rs1_val = Mux(!rs1_rat.busy, rs1, Mux(slot_rs1.rob_state === RobState.Done, slot_rs1.rd_value, 0.U))
  val rs2_val = Mux(!rs2_rat.busy, rs2, Mux(slot_rs2.rob_state === RobState.Done, slot_rs2.rd_value, 0.U))
  val stall   = io.in.valid && (needStall(rs1_rat, slot_rs1) || needStall(rs2_rat, slot_rs2))

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

  // AGU path: next_pc from ROB head in Commit
  val (lsuOp, _)   = LsuOp.safe(FuDecode.take(io.in.bits.fu_op, LsuOp.getWidth))
  io.agu.valid := can_dispatch && (fu_type === FuType.LSU)
  io.agu.bits.base      := rs1_val
  io.agu.bits.imm       := imm
  io.agu.bits.lsuOp     := lsuOp
  io.agu.bits.storeData := rs2_val
  io.agu.bits.rob_id    := rob_id

  io.sysu.valid := can_dispatch && (fu_type === FuType.SYSU)
  io.sysu.bits.rob_id := rob_id

  io.rob_enq.req.valid := can_dispatch
  io.rob_enq.req.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)
  io.rob_enq.req.bits.pred_next_pc := io.in.bits.pred_next_pc
  io.rob_enq.req.bits.might_flush := (fu_type === FuType.BRU)

  val dispatch_fire = outs.map(_.fire).reduce(_ || _)
  io.rat_write.valid := dispatch_fire
  io.rat_write.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)
  io.rat_write.bits.rob_id := rob_id

  io.in.ready := !stall && io.rob_enq.req.ready && Mux1H(fuTypes.map(_ === fu_type), outs.map(_.ready))
}
