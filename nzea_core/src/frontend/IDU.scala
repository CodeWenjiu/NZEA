package nzea_core.frontend

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H, MuxCase, Valid}
import nzea_core.PriorityEncoderTree
import nzea_core.PipeIO
import nzea_core.backend.{FuOpWidth, SysuOp}
import nzea_core.frontend.FuDecode
import nzea_core.retire.IDUCommit
import nzea_config.NzeaConfig

// -------- IDU stage output --------

/** IDU decode result: pc, pred_next_pc, imm, rd_index, physical regs, fu_type, fu_op, fu_src, csr_addr, csr_will_write. */
class IDUOut(width: Int, prfAddrWidth: Int) extends Bundle {
  val pc            = UInt(width.W)
  val imm           = UInt(32.W)
  val rd_index      = UInt(5.W)
  val pred_next_pc  = UInt(width.W)
  val p_rs1         = UInt(prfAddrWidth.W)
  val p_rs2         = UInt(prfAddrWidth.W)
  val old_p_rd      = UInt(prfAddrWidth.W)
  val p_rd          = UInt(prfAddrWidth.W)
  val fu_type       = FuType()
  val fu_op         = UInt(FuOpWidth.Width.W)
  val fu_src        = UInt(FuSrcWidth.Width.W)
  val csr_addr      = UInt(12.W)
  val csr_will_write = Bool()
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
    val restore_rmt = Input(Vec(31, UInt(prfAddrWidth.W)))
  })

  io.in.flush := io.out.flush

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
  val immZ = Cat(0.U(27.W), inst(19, 15))  // zimm for CSR
  val imm = Mux1H(immType.asUInt, Seq(immI, immS, immB, immU, immJ, immZ))

  // restore_free from restore_rmt: free(pr) = (pr not in restore_rmt)
  val restore_free = VecInit((0 until prfDepth).map { pr =>
    val inRmt = (0 until 31).map(i => io.restore_rmt(i) === pr.U).reduce(_ || _)
    !inRmt
  })

  // -------- FreeList bitmap (inline) --------
  // Commit->free delayed 1 cycle: store pending, apply next cycle. Breaks ROB->IDU critical path.
  // Flush from ROB is already delayed 1 cycle; restore_rmt (AMT) settled when flush arrives.
  val pending_free_valid = RegInit(false.B)
  val pending_free_addr  = Reg(UInt(prfAddrWidth.W))

  val free = RegInit(VecInit(
    Seq.tabulate(32)(_ => false.B) ++ Seq.tabulate(prfDepth - 32)(_ => true.B)
  ))
  when(io.out.flush) {
    for (i <- 0 until prfDepth) { free(i) := restore_free(i) }
    pending_free_valid := false.B
  }.otherwise {
    when(pending_free_valid) {
      free(pending_free_addr) := true.B
    }
    when(io.commit.valid && !io.out.flush && io.commit.bits.rd_index =/= 0.U &&
      io.commit.bits.old_p_rd =/= io.commit.bits.p_rd && io.commit.bits.old_p_rd =/= 0.U) {
      pending_free_addr  := io.commit.bits.old_p_rd
      pending_free_valid := true.B
    }.elsewhen(pending_free_valid) {
      pending_free_valid := false.B
    }
  }

  val freeSlice   = VecInit((allocStart until prfDepth).map(i => free(i)))
  val freeBits    = Cat(freeSlice.reverse)
  val encIdx      = PriorityEncoderTree(freeBits)
  val firstFreeIdx = allocStart.U + encIdx
  val prValid    = freeBits.orR

  val needAlloc   = rd_index =/= 0.U
  val renameStall = needAlloc && !prValid
  val canAlloc    = io.out.fire && !renameStall && needAlloc

  when(!io.out.flush && canAlloc) {
    free(firstFreeIdx) := false.B
  }

  // -------- RMT (inline) --------
  val rmt = RegInit(VecInit(Seq.tabulate(31)(i => (i + 1).U(prfAddrWidth.W))))
  when(io.out.flush) {
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
  io.out.bits.rd_index     := rd_index
  io.out.bits.p_rs1        := p_rs1
  io.out.bits.p_rs2        := p_rs2
  io.out.bits.p_rd         := p_rd
  io.out.bits.old_p_rd     := old_p_rd_out
  io.out.bits.fu_type      := fuType
  io.out.bits.fu_op        := fuOp
  io.out.bits.fu_src       := fuSrc
  io.out.bits.csr_addr     := io.in.bits.inst(31, 20)
  // csr_will_write: decode-time; CSRRS/CSRRC use rs1_index=0 (x0), CSRRSI/CSRRCI use zimm=0; same 5 bits inst[19:15]
  val csr_rs1_or_zimm = io.in.bits.inst(19, 15)
  val (sysuOp, _) = SysuOp.safe(FuDecode.take(fuOp, SysuOp.getWidth))
  io.out.bits.csr_will_write := MuxCase(false.B, Seq(
    (sysuOp === SysuOp.CSRRW)  -> true.B,
    (sysuOp === SysuOp.CSRRWI) -> true.B,
    (sysuOp === SysuOp.CSRRS)  -> (csr_rs1_or_zimm =/= 0.U),
    (sysuOp === SysuOp.CSRRC)  -> (csr_rs1_or_zimm =/= 0.U),
    (sysuOp === SysuOp.CSRRSI) -> (csr_rs1_or_zimm =/= 0.U),
    (sysuOp === SysuOp.CSRRCI) -> (csr_rs1_or_zimm =/= 0.U)
  ))
  io.in.ready              := io.out.ready && !renameStall
}
