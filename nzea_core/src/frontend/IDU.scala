package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.PipeIO
import chisel3.util.{Cat, Fill, Mux1H}
import nzea_core.backend.FuOpWidth
import nzea_core.frontend.RatEntry
import nzea_config.NzeaConfig
// -------- IDU stage output --------

/** IDU decode result: pc, pred_next_pc, imm, GPR, rs/rd, fu_type, fu_op (union), fu_src (union), rs1_rat, rs2_rat. */
class IDUOut(width: Int, robIdWidth: Int) extends Bundle {
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
  val rs1_rat   = new RatEntry(robIdWidth)
  val rs2_rat   = new RatEntry(robIdWidth)
}

// -------- IDU module --------

class IDU(addrWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val robIdWidth = chisel3.util.log2Ceil(config.robDepth.max(2))

  val io = IO(new Bundle {
    val in     = Flipped(new PipeIO(new IFUOut(addrWidth)))
    val out    = new PipeIO(new IDUOut(addrWidth, robIdWidth))
    val gpr_wr = Input(new Bundle {
      val addr = UInt(5.W)
      val data = UInt(32.W)
    })
    val rat_isu_write  = Flipped(Valid(new Bundle { val rd_index = UInt(5.W); val rob_id = UInt(robIdWidth.W) }))
    val rat_rob_write  = Flipped(Valid(new Bundle { val rd_index = UInt(5.W); val rob_id = UInt(robIdWidth.W) }))
  })

  // GPR: 32 x 32-bit, x0 fixed to 0
  // Flush propagates from output (id2is) to input (if2id)
  io.in.flush := io.out.flush

  val gpr = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
  when(io.gpr_wr.addr =/= 0.U) {
    gpr(io.gpr_wr.addr) := io.gpr_wr.data
  }

  // RAT: Reg(Vec(32, RatEntry)), two write sources (ISU > ROB), bypass read
  val ratTable = RegInit(VecInit(Seq.fill(32)({
    val e = Wire(new RatEntry(robIdWidth))
    e.rob_id := 0.U
    e.busy   := false.B
    e
  })))

  def bypassRead(idx: UInt): RatEntry = {
    val regVal = ratTable(idx)
    val isuWrite = io.rat_isu_write.valid && io.rat_isu_write.bits.rd_index === idx
    val robWrite = io.rat_rob_write.valid && io.rat_rob_write.bits.rd_index === idx
    val robMatch = regVal.rob_id === io.rat_rob_write.bits.rob_id
    val w = Wire(new RatEntry(robIdWidth))
    w.rob_id := Mux(isuWrite, io.rat_isu_write.bits.rob_id,
      Mux(robWrite && robMatch, regVal.rob_id, regVal.rob_id))
    w.busy := Mux(isuWrite, true.B, Mux(robWrite && robMatch, false.B, regVal.busy))
    w
  }

  when(io.out.flush) {
    for (i <- 0 until 32) {
      ratTable(i).busy := false.B
    }
  }.otherwise {
    when(io.rat_rob_write.valid && io.rat_rob_write.bits.rd_index =/= 0.U &&
         !(io.rat_isu_write.valid && io.rat_isu_write.bits.rd_index === io.rat_rob_write.bits.rd_index)) {
      when(ratTable(io.rat_rob_write.bits.rd_index).rob_id === io.rat_rob_write.bits.rob_id) {
        ratTable(io.rat_rob_write.bits.rd_index).busy := false.B
      }
    }
    when(io.rat_isu_write.valid && io.rat_isu_write.bits.rd_index =/= 0.U) {
      ratTable(io.rat_isu_write.bits.rd_index).rob_id := io.rat_isu_write.bits.rob_id
      ratTable(io.rat_isu_write.bits.rd_index).busy   := true.B
    }
  }

  val decoded = DecodeFields.decodeAll(RiscvInsts.all, io.in.bits.inst, DecodeFields.allWithDefaults)
  val (immType, _) = ImmType.safe(decoded(0))
  val (fuType, _) = FuType.safe(decoded(1))
  val fuOp        = decoded(2)
  val fuSrc       = decoded(3)
  val gprWr       = decoded(4).asBool
  val rs1Rd       = decoded(5).asBool
  val rs2Rd       = decoded(6).asBool

  val rs1_index = Mux(rs1Rd, io.in.bits.inst(19, 15), 0.U(5.W))
  val rs2_index = Mux(rs2Rd, io.in.bits.inst(24, 20), 0.U(5.W))
  val rd_index  = Mux(gprWr, io.in.bits.inst(11, 7), 0.U(5.W))

  val rs1_data = Mux(rs1_index === 0.U, 0.U(32.W), gpr(rs1_index))
  val rs2_data = Mux(rs2_index === 0.U, 0.U(32.W), gpr(rs2_index))

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
  io.out.bits.rs1_index := rs1_index
  io.out.bits.rs2_index := rs2_index
  io.out.bits.rd_index := rd_index
  io.out.bits.fu_type := fuType
  io.out.bits.fu_op    := fuOp
  io.out.bits.fu_src   := fuSrc
  val ratZero = Wire(new RatEntry(robIdWidth))
  ratZero.rob_id := 0.U
  ratZero.busy   := false.B
  io.out.bits.rs1_rat := Mux(rs1_index === 0.U, ratZero, bypassRead(rs1_index))
  io.out.bits.rs2_rat := Mux(rs2_index === 0.U, ratZero, bypassRead(rs2_index))
  io.in.ready := io.out.ready
}
