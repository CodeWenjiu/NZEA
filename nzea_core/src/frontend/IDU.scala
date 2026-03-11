package nzea_core.frontend

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H, PriorityEncoder, Valid}
import nzea_core.PipeIO
import nzea_core.backend.FuOpWidth
import nzea_core.retire.IDUCommit
import nzea_config.NzeaConfig

// -------- IDU stage output --------

/** IDU decode result: pc, pred_next_pc, imm, rs1/rs2/rd indices, physical regs, fu_type, fu_op, fu_src. */
class IDUOut(width: Int, prfAddrWidth: Int) extends Bundle {
  val pc           = UInt(width.W)
  val pred_next_pc = UInt(width.W)
  val imm          = UInt(32.W)
  val rs1_index    = UInt(5.W)
  val rs2_index    = UInt(5.W)
  val rd_index     = UInt(5.W)
  val p_rs1        = UInt(prfAddrWidth.W)
  val p_rs2        = UInt(prfAddrWidth.W)
  val p_rd         = UInt(prfAddrWidth.W)
  val old_p_rd     = UInt(prfAddrWidth.W)
  val fu_type      = FuType()
  val fu_op        = UInt(FuOpWidth.Width.W)
  val fu_src       = UInt(FuSrcWidth.Width.W)
}

// -------- IDU module --------

class IDU(addrWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val prfDepth     = config.prfDepth
  private val prfAddrWidth = chisel3.util.log2Ceil(prfDepth)
  private val allocStart   = 1  // PR0 reserved for x0

  val io = IO(new Bundle {
    val in          = Flipped(new PipeIO(new IFUOut(addrWidth)))
    val out         = new PipeIO(new IDUOut(addrWidth, prfAddrWidth))
    val commit      = Input(Valid(new IDUCommit(prfAddrWidth)))
    val flush       = Input(Bool())
    val restore_rmt = Input(Vec(31, UInt(prfAddrWidth.W)))
  })

  io.in.flush := io.out.flush || io.flush

  // -------- Decode --------

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

  // restore_free from restore_rmt: free(pr) = (pr not in restore_rmt)
  val restore_free = VecInit((0 until prfDepth).map { pr =>
    val inRmt = (0 until 31).map(i => io.restore_rmt(i) === pr.U).reduce(_ || _)
    !inRmt
  })

  // -------- FreeList bitmap (inline) --------
  val free = RegInit(VecInit(
    Seq.tabulate(32)(_ => false.B) ++ Seq.tabulate(prfDepth - 32)(_ => true.B)
  ))
  when(io.flush) {
    for (i <- 0 until prfDepth) { free(i) := restore_free(i) }
  }.otherwise {
    when(io.commit.valid && io.commit.bits.rd_index =/= 0.U &&
      io.commit.bits.old_p_rd =/= io.commit.bits.p_rd && io.commit.bits.old_p_rd =/= 0.U) {
      free(io.commit.bits.old_p_rd) := true.B
    }
  }

  val freeSlice   = VecInit((allocStart until prfDepth).map(i => free(i)))
  val freeBits    = Cat(freeSlice.reverse)
  val encIdx      = PriorityEncoder(freeBits)
  val firstFreeIdx = allocStart.U + encIdx
  val prValid    = freeBits.orR

  val needAlloc   = rd_index =/= 0.U
  val renameStall = needAlloc && !prValid
  val canAlloc    = io.out.fire && !renameStall && needAlloc

  when(!io.flush && canAlloc) {
    free(firstFreeIdx) := false.B
  }

  // -------- RMT (inline) --------
  val rmt = RegInit(VecInit(Seq.tabulate(31)(i => (i + 1).U(prfAddrWidth.W))))
  when(io.flush) {
    for (i <- 0 until 31) { rmt(i) := io.restore_rmt(i) }
  }.elsewhen(canAlloc) {
    rmt(rd_index - 1.U) := firstFreeIdx
  }

  def readPr(ar: UInt): UInt = Mux(ar === 0.U, 0.U(prfAddrWidth.W), rmt(ar - 1.U))
  val p_rs1    = readPr(rs1_index)
  val p_rs2    = readPr(rs2_index)
  val old_p_rd = readPr(rd_index)

  val p_rd         = Mux(canAlloc, firstFreeIdx, 0.U(prfAddrWidth.W))
  val old_p_rd_out = Mux(needAlloc, old_p_rd, 0.U(prfAddrWidth.W))

  // -------- Output --------

  io.out.valid := io.in.valid && !renameStall
  io.out.bits.pc           := io.in.bits.pc
  io.out.bits.pred_next_pc := io.in.bits.pred_next_pc
  io.out.bits.imm          := imm
  io.out.bits.rs1_index    := rs1_index
  io.out.bits.rs2_index    := rs2_index
  io.out.bits.rd_index     := rd_index
  io.out.bits.p_rs1        := p_rs1
  io.out.bits.p_rs2        := p_rs2
  io.out.bits.p_rd         := p_rd
  io.out.bits.old_p_rd     := old_p_rd_out
  io.out.bits.fu_type      := fuType
  io.out.bits.fu_op        := fuOp
  io.out.bits.fu_src      := fuSrc
  io.in.ready              := io.out.ready && !renameStall
}
