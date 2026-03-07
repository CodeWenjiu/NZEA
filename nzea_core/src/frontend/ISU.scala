package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, MuxLookup, Valid}
import nzea_core.PipeIO
import nzea_config.NzeaConfig
import nzea_core.backend.{AluInput, AluOp, AguInput, BruInput, BruOp, LsuOp, SysuInput}
import nzea_core.retire.rob.{RobEnqIO, RobSlotRead, RobSlotReadPort}
import nzea_core.retire.CommitMsg

/** ISU: GPR + RAT inside; on dispatch RAT(rd):=rob_id,busy:=true; on commit write GPR, clear busy; read rs: busy+is_done→slot else !busy→gpr else stall. */
class ISU(addrWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val robDepth   = config.robDepth
  private val robIdWidth = chisel3.util.log2Ceil(robDepth.max(2))

  val io = IO(new Bundle {
    val in             = Flipped(new PipeIO(new IDUOut(addrWidth)))
    val rob_enq        = Flipped(new RobEnqIO(robIdWidth))
    val rob_slot_rs1   = Flipped(new RobSlotReadPort(robIdWidth))
    val rob_slot_rs2   = Flipped(new RobSlotReadPort(robIdWidth))
    val rob_commit     = Input(Valid(new CommitMsg))
    val rat_rob_write  = Input(Valid(new Bundle { val rd_index = UInt(5.W); val rob_id = UInt(robIdWidth.W) }))
    val alu            = new PipeIO(new AluInput(robIdWidth))
    val bru            = new PipeIO(new BruInput(robIdWidth))
    val agu            = new PipeIO(new AguInput(robIdWidth))
    val sysu           = new PipeIO(new SysuInput(robIdWidth))
  })

  // -------- GPR: written on rob_commit --------
  val gpr = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
  when(io.rob_commit.valid && io.rob_commit.bits.rd_index =/= 0.U) {
    gpr(io.rob_commit.bits.rd_index) := io.rob_commit.bits.rd_value
  }

  // -------- RAT: all writes unified here --------
  val ratTable_rob_id = RegInit(VecInit(Seq.fill(32)(0.U(robIdWidth.W))))
  val ratTable_busy   = RegInit(VecInit(Seq.fill(32)(false.B)))
  val outs            = Seq(io.alu, io.bru, io.agu, io.sysu)
  val dispatch_fire   = outs.map(_.fire).reduce(_ || _)
  when(io.in.flush) {
    for (i <- 0 until 32) { ratTable_busy(i) := false.B }
  }.otherwise {
    // commit clear: can fire same cycle as dispatch
    when(io.rat_rob_write.valid && io.rat_rob_write.bits.rd_index =/= 0.U) {
      val idx = io.rat_rob_write.bits.rd_index
      when(ratTable_rob_id(idx) === io.rat_rob_write.bits.rob_id) {
        ratTable_busy(idx) := false.B
      }
    }
    // dispatch set: overwrites commit when same rd (new producer wins)
    when(dispatch_fire && io.in.bits.rd_index =/= 0.U) {
      ratTable_rob_id(io.in.bits.rd_index) := io.rob_enq.rob_id
      ratTable_busy(io.in.bits.rd_index)   := true.B
    }
  }

  val fu_type   = io.in.bits.fu_type
  val fu_src    = io.in.bits.fu_src
  val rs1_index = io.in.bits.rs1_index
  val rs2_index = io.in.bits.rs2_index
  val imm       = io.in.bits.imm
  val pc        = io.in.bits.pc
  val rob_id    = io.rob_enq.rob_id

  // RAT lookup
  val rs1_rat = Wire(new RatEntry(robIdWidth))
  rs1_rat.rob_id := ratTable_rob_id(rs1_index)
  rs1_rat.busy   := ratTable_busy(rs1_index)
  val rs2_rat = Wire(new RatEntry(robIdWidth))
  rs2_rat.rob_id := ratTable_rob_id(rs2_index)
  rs2_rat.busy   := ratTable_busy(rs2_index)

  io.rob_slot_rs1.rob_id := rs1_rat.rob_id
  io.rob_slot_rs2.rob_id := rs2_rat.rob_id
  val slot_rs1 = io.rob_slot_rs1.slot
  val slot_rs2 = io.rob_slot_rs2.slot

  // Read rs: if busy then slot (is_done→value else stall); if !busy then GPR. Both ready → dispatch.
  def readReg(index: UInt, rat: RatEntry, slot: RobSlotRead): (UInt, Bool) = {
    val fromGpr  = Mux(index === 0.U, 0.U(32.W), gpr(index))
    val fromSlot = slot.rd_value
    val needStall = rat.busy && !slot.is_done
    val val_ = Mux(!rat.busy, fromGpr, fromSlot)
    (val_, needStall)
  }

  val (rs1_val, rs1_stall) = readReg(rs1_index, rs1_rat, slot_rs1)
  val (rs2_val, rs2_stall) = readReg(rs2_index, rs2_rat, slot_rs2)
  val stall = io.in.valid && (rs1_stall || rs2_stall)

  // Flush propagates from output (is2ex) to input (id2is)
  val fuTypes = Seq(FuType.ALU, FuType.BRU, FuType.LSU, FuType.SYSU)
  io.in.flush := outs.map(_.flush).reduce(_ || _)

  // ALU path
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
  io.alu.bits.opA    := aluOpA
  io.alu.bits.opB    := aluOpB
  io.alu.bits.aluOp  := aluOp
  io.alu.bits.pc     := pc
  io.alu.bits.rob_id := rob_id

  // BRU path
  val (bruOp, _) = BruOp.safe(FuDecode.take(io.in.bits.fu_op, BruOp.getWidth))
  io.bru.valid := can_dispatch && (fu_type === FuType.BRU)
  io.bru.bits.pc           := pc
  io.bru.bits.pred_next_pc := io.in.bits.pred_next_pc
  io.bru.bits.offset       := imm
  io.bru.bits.rs1          := rs1_val
  io.bru.bits.rs2          := rs2_val
  io.bru.bits.bruOp        := bruOp
  io.bru.bits.rob_id       := rob_id

  // AGU path
  val (lsuOp, _) = LsuOp.safe(FuDecode.take(io.in.bits.fu_op, LsuOp.getWidth))
  io.agu.valid := can_dispatch && (fu_type === FuType.LSU)
  io.agu.bits.base      := rs1_val
  io.agu.bits.imm       := imm
  io.agu.bits.lsuOp     := lsuOp
  io.agu.bits.storeData := rs2_val
  io.agu.bits.pc        := pc
  io.agu.bits.rob_id    := rob_id

  io.sysu.valid := can_dispatch && (fu_type === FuType.SYSU)
  io.sysu.bits.rob_id := rob_id
  io.sysu.bits.pc     := pc

  io.rob_enq.req.valid := can_dispatch
  io.rob_enq.req.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)
  io.rob_enq.req.bits.might_flush := (fu_type === FuType.BRU)

  io.in.ready := !stall && io.rob_enq.req.ready && Mux1H(fuTypes.map(_ === fu_type), outs.map(_.ready))
}
