package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, MuxLookup, Valid}
import nzea_core.backend.fu.{AluInput, AluOp, AguInput, BruInput, BruOp, LsuOp, SysuInput}
import nzea_core.backend.{RobEntry, WbBypass}

/** ISU: route by fu_type; ALU/BRU/LSU/SYSU; on dispatch, enqueues Rob entry (fu_type, rd_index). */
class ISU(addrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in            = Flipped(Decoupled(new IDUOut(addrWidth)))
    val rob_pending_rd = Input(Vec(4, Valid(UInt(5.W))))
    val wb_bypass     = Input(Valid(new WbBypass))
    val rob_enq       = Decoupled(new RobEntry)
    val alu           = Decoupled(new AluInput)
    val bru     = Decoupled(new BruInput)
    val agu     = Decoupled(new AguInput)
    val sysu    = Decoupled(new SysuInput)
  })

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

  val can_bypass_rs1 = io.wb_bypass.valid && rs1_index =/= 0.U && io.wb_bypass.bits.rd === rs1_index
  val can_bypass_rs2 = io.wb_bypass.valid && rs2_index =/= 0.U && io.wb_bypass.bits.rd === rs2_index
  val rs1_val = Mux(can_bypass_rs1, io.wb_bypass.bits.data, rs1)
  val rs2_val = Mux(can_bypass_rs2, io.wb_bypass.bits.data, rs2)

  val conflict_rs1 = io.rob_pending_rd.map { s => s.valid && s.bits === rs1_index && rs1_index =/= 0.U }.reduce(_ || _)
  val conflict_rs2 = io.rob_pending_rd.map { s => s.valid && s.bits === rs2_index && rs2_index =/= 0.U }.reduce(_ || _)
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

  io.alu.valid := io.in.valid && !stall && (fu_type === FuType.ALU)
  io.alu.bits.opA   := aluOpA
  io.alu.bits.opB   := aluOpB
  io.alu.bits.aluOp := aluOp
  io.alu.bits.pc    := pc

  // BRU path: pass pc, offset (imm), use_rs1_imm; BRU computes target = pc+offset or (rs1+offset)&~1
  val (bruSrc, _)   = BruSrc.safe(FuDecode.take(fu_src, BruSrc.getWidth))
  val (bruOp, _)    = BruOp.safe(FuDecode.take(io.in.bits.fu_op, BruOp.getWidth))

  io.bru.valid := io.in.valid && !stall && (fu_type === FuType.BRU)
  io.bru.bits.pc          := pc
  io.bru.bits.offset      := imm
  io.bru.bits.use_rs1_imm := (bruSrc === BruSrc.Rs1Imm)
  io.bru.bits.rs1         := rs1_val
  io.bru.bits.rs2         := rs2_val
  io.bru.bits.bruOp  := bruOp

  // AGU path: addr = rs1+imm (IS stage), lsuOp from fu_op as LsuOp ChiselEnum
  val aguAddr      = rs1_val + imm
  val (lsuOp, _)   = LsuOp.safe(FuDecode.take(io.in.bits.fu_op, LsuOp.getWidth))
  io.agu.valid := io.in.valid && !stall && (fu_type === FuType.LSU)
  io.agu.bits.addr      := aguAddr
  io.agu.bits.lsuOp     := lsuOp
  io.agu.bits.storeData := rs2_val
  io.agu.bits.pc        := pc

  io.sysu.valid := io.in.valid && !stall && (fu_type === FuType.SYSU)
  io.sysu.bits.pc := pc

  io.rob_enq.valid   := io.in.valid && !stall
  io.rob_enq.bits.fu_type  := fu_type
  io.rob_enq.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)

  io.in.ready := !stall && io.rob_enq.ready && Mux1H(fuTypes.map(_ === fu_type), outs.map(_.ready))
}
