package nzea_core.frontend

import chisel3._
import nzea_rtl.PipeIO
import nzea_config.NzeaConfig
import nzea_core.backend.integer.IntegerIssueQueueEntry
import nzea_core.backend.integer.{AluOp, BruOp, DivOp, LsuOp, MulOp, SysuOp}
import nzea_core.retire.rob.{RobEnqIO, RobMemType, LsAllocIO}
/** ISU factory. */
object ISU {
  def apply(addrWidth: Int)(implicit config: NzeaConfig): ISU =
    Module(new ISU(addrWidth))
}

/** ISU: Issue Unit. Outputs unified [[IntegerIssueQueueEntry]] to the integer issue queue (no per-port dispatch).
  * rs1/rs2 ready are placeholders; issue queue reads PRF + bypass when writing a slot.
  */
class ISU(addrWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val robDepth     = config.robDepth
  private val robIdWidth   = chisel3.util.log2Ceil(robDepth.max(2))
  private val prfAddrWidth = config.prfAddrWidth
  private val lsqIdWidth   = chisel3.util.log2Ceil((robDepth / 2).max(1).max(2))

  val io = IO(new Bundle {
    val in              = Flipped(new PipeIO(new IDUOut(addrWidth, prfAddrWidth)))
    val out             = new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))
    val rob_enq         = Flipped(new RobEnqIO(robIdWidth, prfAddrWidth))
    val ls_alloc        = Flipped(new LsAllocIO(robIdWidth, prfAddrWidth, lsqIdWidth))
  })

  io.in.flush := io.out.flush

  val fu_type      = io.in.bits.fu_type
  val fu_src       = io.in.bits.fu_src
  val fu_op        = io.in.bits.fu_op
  val imm          = io.in.bits.imm
  val pc           = io.in.bits.pc
  val rob_id       = io.rob_enq.rob_id
  val can_dispatch = io.in.valid

  val lsu_can_alloc = Mux(fu_type === FuType.LSU, io.ls_alloc.ready, true.B)
  val can_push      = can_dispatch && io.rob_enq.req.ready && lsu_can_alloc

  val (lsuOp, _) = LsuOp.safe(FuDecode.take(fu_op, LsuOp.getWidth))
  io.rob_enq.req.valid := can_push && io.out.ready
  io.ls_alloc.valid := can_push && io.out.ready && (fu_type === FuType.LSU)
  io.ls_alloc.bits.rob_id := rob_id
  io.ls_alloc.bits.p_rd := io.in.bits.p_rd
  io.ls_alloc.bits.lsuOp := lsuOp.asUInt
  io.rob_enq.req.bits.rd_index := io.in.bits.rd_index
  io.rob_enq.req.bits.might_flush := (fu_type === FuType.BRU)
  io.rob_enq.req.bits.mem_type := Mux(
    fu_type === FuType.LSU,
    Mux(LsuOp.isLoad(lsuOp), RobMemType.Load, RobMemType.Store),
    RobMemType.None
  )
  io.rob_enq.req.bits.p_rd := io.in.bits.p_rd
  io.rob_enq.req.bits.old_p_rd := io.in.bits.old_p_rd

  io.out.valid := can_push
  io.out.bits.fu_type      := fu_type
  io.out.bits.rs1_ready    := false.B
  io.out.bits.rs2_ready    := false.B
  io.out.bits.p_rs1        := io.in.bits.p_rs1
  io.out.bits.p_rs2        := io.in.bits.p_rs2
  io.out.bits.imm          := imm
  io.out.bits.pc           := pc
  io.out.bits.pred_next_pc := io.in.bits.pred_next_pc
  io.out.bits.fu_op        := fu_op
  io.out.bits.fu_src       := fu_src
  io.out.bits.csr_addr       := io.in.bits.csr_addr
  io.out.bits.csr_will_write := io.in.bits.csr_will_write
  io.out.bits.rob_id         := rob_id
  io.out.bits.p_rd         := io.in.bits.p_rd
  io.out.bits.old_p_rd     := io.in.bits.old_p_rd
  io.out.bits.rd_index     := io.in.bits.rd_index
  io.out.bits.lsq_id := Mux(fu_type === FuType.LSU, io.ls_alloc.lsq_id, 0.U(lsqIdWidth.W))
  io.out.bits.mem_type := Mux(
    fu_type === FuType.LSU,
    Mux(LsuOp.isLoad(lsuOp), RobMemType.Load, RobMemType.Store),
    RobMemType.None
  )

  io.in.ready := io.rob_enq.req.ready && lsu_can_alloc && io.out.ready
}
