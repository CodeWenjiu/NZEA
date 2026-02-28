package nzea_core.frontend

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.{Cat, Fill, Mux1H}
import nzea_core.backend.FuOpWidth
// -------- IDU stage output --------

/** IDU decode result: pc, pred_next_pc, imm, GPR, rs/rd, fu_type, fu_op (union), fu_src (union). */
class IDUOut(width: Int) extends Bundle {
  val pc           = UInt(width.W)
  val pred_next_pc = UInt(width.W)
  val imm       = UInt(32.W)
  val rs1       = UInt(32.W)
  val rs2       = UInt(32.W)
  val rs1_index = UInt(5.W)
  val rs2_index = UInt(5.W)
  val rd_index  = UInt(5.W)
  val fu_type   = FuType()
  val fu_op     = UInt(FuOpWidth.Width.W)
  val fu_src    = UInt(FuSrcWidth.Width.W)
}

// -------- IDU module --------

class IDU(addrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new IFUOut(addrWidth)))
    val out    = Decoupled(new IDUOut(addrWidth))
    val gpr_wr = Input(new Bundle {
      val addr = UInt(5.W)
      val data = UInt(32.W)
    })
  })

  // GPR: 32 x 32-bit, x0 fixed to 0
  val gpr = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
  when(io.gpr_wr.addr =/= 0.U) {
    gpr(io.gpr_wr.addr) := io.gpr_wr.data
  }

  val rs1 = io.in.bits.inst(19, 15)
  val rs2 = io.in.bits.inst(24, 20)
  val rs1_data = Mux(rs1 === 0.U, 0.U(32.W), gpr(rs1))
  val rs2_data = Mux(rs2 === 0.U, 0.U(32.W), gpr(rs2))

  val decoded = DecodeFields.decodeAll(RiscvInsts.all, io.in.bits.inst, DecodeFields.allWithDefaults)
  val (immType, _) = ImmType.safe(decoded(0))
  val (fuType, _)  = FuType.safe(decoded(1))
  val fuOp         = decoded(2)
  val fuSrc        = decoded(3)

  val rd = io.in.bits.inst(11, 7)

  val inst = io.in.bits.inst
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  val imm = Mux1H(immType.asUInt, Seq(immI, immS, immB, immU, immJ))

  io.out.valid := io.in.valid
  io.out.bits.pc           := io.in.bits.pc
  io.out.bits.pred_next_pc := io.in.bits.pred_next_pc
  io.out.bits.imm     := imm
  io.out.bits.rs1      := rs1_data
  io.out.bits.rs2      := rs2_data
  io.out.bits.rs1_index := rs1
  io.out.bits.rs2_index := rs2
  io.out.bits.rd_index := rd
  io.out.bits.fu_type  := fuType
  io.out.bits.fu_op    := fuOp
  io.out.bits.fu_src   := fuSrc
  io.in.ready := io.out.ready
}
