package nzea_core.frontend

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H, Valid}
import nzea_core.PipeIO
import nzea_core.backend.FuOpWidth
import nzea_core.rename.{FreeList, RMT}
import nzea_config.NzeaConfig

// -------- IDU stage output --------

/** IDU decode result: pc, pred_next_pc, imm, rs1/rs2/rd indices, physical regs (p_rs1, p_rs2, p_rd, old_p_rd), fu_type, fu_op, fu_src. */
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

  val io = IO(new Bundle {
    val in     = Flipped(new PipeIO(new IFUOut(addrWidth)))
    val out    = new PipeIO(new IDUOut(addrWidth, prfAddrWidth))
    val commit = Input(Valid(new Bundle { val rd_index = UInt(5.W); val p_rd = UInt(prfAddrWidth.W); val old_p_rd = UInt(prfAddrWidth.W) }))
    val flush  = Input(Bool())  // direct from Rob do_flush, for RMT/FreeList restore
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

  // All three reg indices must go through IDU mux: only pass when explicitly needed (gprWr/rs1Rd/rs2Rd), else 0
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

  // -------- RMT & FreeList (Rename) --------
  // RMT receives muxed indices: rs1/rs2/rd only when needed, else 0 -> maps to PR0

  val rmt      = Module(new RMT(prfDepth))
  val freeList = Module(new FreeList(prfDepth))

  rmt.io.read(0).ar := rs1_index
  rmt.io.read(1).ar := rs2_index
  rmt.io.read(2).ar := rd_index
  val p_rs1    = rmt.io.read(0).pr
  val p_rs2    = rmt.io.read(1).pr
  val old_p_rd = rmt.io.read(2).pr

  val needAlloc    = rd_index =/= 0.U && fuType =/= FuType.SYSU
  val canOutput    = io.out.ready && io.in.valid
  val renameStall  = needAlloc && !freeList.io.pr.valid
  val canAlloc     = canOutput && !renameStall && needAlloc

  freeList.io.pr.ready := canAlloc
  // Main FreeList: push on commit when !flush.
  // Do NOT push when old_p_rd == p_rd: we're reusing the same PR, not freeing it.
  freeList.io.push.valid := io.commit.valid && io.commit.bits.rd_index =/= 0.U && !io.flush &&
    io.commit.bits.old_p_rd =/= io.commit.bits.p_rd
  freeList.io.push.bits  := io.commit.bits.old_p_rd

  val p_rd = Mux(canAlloc, freeList.io.pr.bits, 0.U(prfAddrWidth.W))
  val old_p_rd_out = Mux(needAlloc, old_p_rd, 0.U(prfAddrWidth.W))

  rmt.io.write.valid := canAlloc
  rmt.io.write.bits.ar := rd_index
  rmt.io.write.bits.pr := p_rd

  // Checkpoint: updated on commit (independent of branch), restored on flush
  rmt.io.commit.valid := io.commit.valid
  rmt.io.commit.bits.rd_index := io.commit.bits.rd_index
  rmt.io.commit.bits.p_rd := io.commit.bits.p_rd
  rmt.io.commit.bits.old_p_rd := io.commit.bits.old_p_rd
  rmt.io.flush := io.in.flush

  freeList.io.commit.valid := io.commit.valid && io.commit.bits.rd_index =/= 0.U &&
    io.commit.bits.old_p_rd =/= io.commit.bits.p_rd
  freeList.io.commit.bits := io.commit.bits.old_p_rd
  freeList.io.flush := io.in.flush

  // -------- Output --------

  io.out.valid := io.in.valid
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
  io.out.bits.fu_src       := fuSrc
  io.in.ready              := io.out.ready && !renameStall
}
