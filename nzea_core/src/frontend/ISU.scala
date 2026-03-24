package nzea_core.frontend

import chisel3._
import chisel3.util.Valid
import nzea_core.PipeIO
import nzea_config.NzeaConfig
import nzea_core.backend.{AluOp, BruOp, DivOp, LsuOp, MulOp, SysuOp}
import nzea_core.retire.rob.{RobEnqIO, RobMemType, LsAllocIO}
import nzea_config.FuConfig

/** ISU factory: config-driven, port count derived from FuConfig. */
object ISU {
  def apply(addrWidth: Int)(implicit config: NzeaConfig): ISU =
    Module(new ISU(addrWidth, FuConfig.numPrfWritePorts))
}

/** ISU: Issue Unit. Outputs unified IssueQueueEntry to IssueQueue (no per-port dispatch).
  * PRF storage is [[Prf]]; ISU merges bypass + raw PRF read for enqueue rs1/rs2 ready only.
  */
class ISU(addrWidth: Int, numPrfWritePorts: Int)(implicit config: NzeaConfig) extends Module {
  private val robDepth     = config.robDepth
  private val robIdWidth   = chisel3.util.log2Ceil(robDepth.max(2))
  private val prfAddrWidth = config.prfAddrWidth
  private val lsqIdWidth   = chisel3.util.log2Ceil((robDepth / 2).max(1).max(2))

  val io = IO(new Bundle {
    val in              = Flipped(new PipeIO(new IDUOut(addrWidth, prfAddrWidth)))
    val out             = new PipeIO(new IssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))
    val rob_enq         = Flipped(new RobEnqIO(robIdWidth, prfAddrWidth))
    val ls_alloc        = Flipped(new LsAllocIO(robIdWidth, prfAddrWidth, lsqIdWidth))
    val prf_write       = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val bypass_level1   = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    /** Raw PRF read at p_rs1 / p_rs2 (from [[Prf]]); bypass merged here for IssueQueueEntry.ready. */
    val prf_enqueue_rs1 = Input(new PrfRawRead(prfAddrWidth))
    val prf_enqueue_rs2 = Input(new PrfRawRead(prfAddrWidth))
  })

  val (_, rs1_ready) = PrfBypass.mergeOperand(
    io.in.bits.p_rs1,
    io.prf_enqueue_rs1.data,
    io.prf_enqueue_rs1.ready,
    io.bypass_level1,
    io.prf_write
  )
  val (_, rs2_ready) = PrfBypass.mergeOperand(
    io.in.bits.p_rs2,
    io.prf_enqueue_rs2.data,
    io.prf_enqueue_rs2.ready,
    io.bypass_level1,
    io.prf_write
  )

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
  io.rob_enq.req.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)
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
  io.out.bits.rs1_ready    := rs1_ready
  io.out.bits.rs2_ready    := rs2_ready
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
