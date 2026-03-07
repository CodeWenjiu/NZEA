package nzea_core.frontend

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H}
import nzea_core.PipeIO
import nzea_core.backend.FuOpWidth
import nzea_config.NzeaConfig

// -------- IDU stage output --------

/** IDU decode result: pc, pred_next_pc, imm, rs1/rs2/rd indices, fu_type, fu_op, fu_src. */
class IDUOut(width: Int) extends Bundle {
  val pc           = UInt(width.W)
  val pred_next_pc = UInt(width.W)
  val imm          = UInt(32.W)
  val rs1_index    = UInt(5.W)
  val rs2_index    = UInt(5.W)
  val rd_index     = UInt(5.W)
  val fu_type      = FuType()
  val fu_op        = UInt(FuOpWidth.Width.W)
  val fu_src       = UInt(FuSrcWidth.Width.W)
}

// -------- IDU module --------

class IDU(addrWidth: Int)(implicit config: NzeaConfig) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new IFUOut(addrWidth)))
    val out = new PipeIO(new IDUOut(addrWidth))
  })

  io.in.flush := io.out.flush

  val decoded = DecodeFields.decodeAll(RiscvInsts.all, io.in.bits.inst, DecodeFields.allWithDefaults)
  val (immType, _) = ImmType.safe(decoded(0))
  val (fuType, _)  = FuType.safe(FuDecode.take(decoded(1), FuType.getWidth))
  val fuOp         = decoded(2)
  val fuSrc        = decoded(3)
  val gprWr        = decoded(4).asBool
  val rs1Rd        = decoded(5).asBool
  val rs2Rd        = decoded(6).asBool

  val rs1_index = Mux(rs1Rd, io.in.bits.inst(19, 15), 0.U(5.W))
  val rs2_index = Mux(rs2Rd, io.in.bits.inst(24, 20), 0.U(5.W))
  val rd_index  = Mux(gprWr, io.in.bits.inst(11, 7), 0.U(5.W))

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
  io.out.bits.imm          := imm
  io.out.bits.rs1_index    := rs1_index
  io.out.bits.rs2_index    := rs2_index
  io.out.bits.rd_index     := rd_index
  io.out.bits.fu_type      := fuType
  io.out.bits.fu_op        := fuOp
  io.out.bits.fu_src       := fuSrc
  io.in.ready              := io.out.ready
}
