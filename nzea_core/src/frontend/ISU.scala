package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, MuxLookup}
import nzea_core.backend.fu.{AluInput, AluOp, AguInput, BruInput, BruOp, LsuOp, SysuInput}
import nzea_core.backend.RobEntry

/** ISU: route by fu_type; ALU/BRU/LSU/SYSU; on dispatch, enqueues Rob entry (fu_type, rd_index). */
class ISU(addrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new IDUOut(addrWidth)))
    val rob_enq = Decoupled(new RobEntry)
    val alu     = Decoupled(new AluInput)
    val bru     = Decoupled(new BruInput)
    val agu     = Decoupled(new AguInput)
    val sysu    = Decoupled(new SysuInput)
  })

  val fu_type = io.in.bits.fu_type
  val fu_src  = io.in.bits.fu_src
  val outs    = Seq(io.alu, io.bru, io.agu, io.sysu)
  val fuTypes = Seq(FuType.ALU, FuType.BRU, FuType.LSU, FuType.SYSU)

  val rs1 = io.in.bits.rs1
  val rs2 = io.in.bits.rs2
  val imm = io.in.bits.imm
  val pc  = io.in.bits.pc

  // ALU path: FuDecode.take slices by enum width; no manual bit-width when AluSrc/AluOp change
  val (aluSrc, _) = AluSrc.safe(FuDecode.take(fu_src, AluSrc.getWidth))
  val aluOpA = MuxLookup(aluSrc.asUInt, rs1)(Seq(
    AluSrc.ImmZero.asUInt -> imm,
    AluSrc.PcImm.asUInt   -> pc
  ))
  val aluOpB = MuxLookup(aluSrc.asUInt, rs2)(Seq(
    AluSrc.Rs1Imm.asUInt  -> imm,
    AluSrc.ImmZero.asUInt -> 0.U(32.W),
    AluSrc.PcImm.asUInt   -> imm
  ))
  val aluOp = AluOp.safe(FuDecode.take(io.in.bits.fu_op, AluOp.getWidth))._1

  io.alu.valid := io.in.valid && (fu_type === FuType.ALU)
  io.alu.bits.opA   := aluOpA
  io.alu.bits.opB   := aluOpB
  io.alu.bits.aluOp := aluOp
  io.alu.bits.pc    := pc

  // BRU path: pass pc, offset (imm), use_rs1_imm; BRU computes target = pc+offset or (rs1+offset)&~1
  val (bruSrc, _)   = BruSrc.safe(FuDecode.take(fu_src, BruSrc.getWidth))
  val (bruOp, _)    = BruOp.safe(FuDecode.take(io.in.bits.fu_op, BruOp.getWidth))

  io.bru.valid := io.in.valid && (fu_type === FuType.BRU)
  io.bru.bits.pc          := pc
  io.bru.bits.offset      := imm
  io.bru.bits.use_rs1_imm := (bruSrc === BruSrc.Rs1Imm)
  io.bru.bits.rs1         := rs1
  io.bru.bits.rs2    := rs2
  io.bru.bits.bruOp  := bruOp

  // AGU path: addr = rs1+imm (IS stage), lsuOp from fu_op as LsuOp ChiselEnum
  val aguAddr      = rs1 + imm
  val (lsuOp, _)   = LsuOp.safe(FuDecode.take(io.in.bits.fu_op, LsuOp.getWidth))
  io.agu.valid := io.in.valid && (fu_type === FuType.LSU)
  io.agu.bits.addr      := aguAddr
  io.agu.bits.lsuOp     := lsuOp
  io.agu.bits.storeData := rs2
  io.agu.bits.pc        := pc

  io.sysu.valid := io.in.valid && (fu_type === FuType.SYSU)
  io.sysu.bits.pc := pc

  io.rob_enq.valid   := io.in.valid
  io.rob_enq.bits.fu_type  := fu_type
  io.rob_enq.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)

  io.in.ready := io.rob_enq.ready && Mux1H(fuTypes.map(_ === fu_type), outs.map(_.ready))
}
