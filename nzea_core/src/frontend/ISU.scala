package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, MuxLookup}
import nzea_core.backend.fu.{AluInput, AluOp, BruInput, BruOp}

/** ISU: route by fu_type; ALU uses alu_src for opA/opB; BRU gets target, rs1, rs2, bruOp; BRU derives is_jmp/taken internally. */
class ISU(addrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new IDUOut(addrWidth)))
    val alu  = Decoupled(new AluInput)
    val bru  = Decoupled(new BruInput)
    val lsu  = Decoupled(new Bundle {})
    val sysu = Decoupled(new Bundle {})
  })

  val fu_type = io.in.bits.fu_type
  val fu_src  = io.in.bits.fu_src
  val outs    = Seq(io.alu, io.bru, io.lsu, io.sysu)
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
  io.alu.bits.opA      := aluOpA
  io.alu.bits.opB      := aluOpB
  io.alu.bits.aluOp    := aluOp
  io.alu.bits.rd_index := io.in.bits.rd_index

  // BRU path: FuDecode.take slices by enum width; no is_jmp, BRU derives it from bruOp
  val (bruSrc, _) = BruSrc.safe(FuDecode.take(fu_src, BruSrc.getWidth))
  val bruOp       = FuDecode.take(io.in.bits.fu_op, BruOp.getWidth)
  val jmpAdd      = pc + imm
  val jalrTarget  = (rs1 + imm) & ~1.U(32.W)
  val target      = Mux(bruSrc === BruSrc.Rs1Imm, jalrTarget, jmpAdd)

  io.bru.valid := io.in.valid && (fu_type === FuType.BRU)
  io.bru.bits.target   := target
  io.bru.bits.rs1      := rs1
  io.bru.bits.rs2      := rs2
  io.bru.bits.bruOp    := bruOp
  io.bru.bits.pc       := pc
  io.bru.bits.rd_index := io.in.bits.rd_index

  Seq((io.lsu, FuType.LSU), (io.sysu, FuType.SYSU)).foreach { case (out, t) =>
    out.valid := io.in.valid && (fu_type === t)
    out.bits := DontCare
  }
  io.in.ready := Mux1H(fuTypes.map(_ === fu_type), outs.map(_.ready))
}
