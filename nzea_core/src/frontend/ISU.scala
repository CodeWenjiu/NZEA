package nzea_core.frontend

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, Valid, switch, is}
import nzea_core.PipeIO
import nzea_config.NzeaConfig
import nzea_core.backend.{AluOp, BruOp, DivOp, LsuOp, MulOp, SysuOp}
import nzea_core.retire.rob.{RobEnqIO, RobMemType, LsAllocIO}
import nzea_config.FuConfig

/** CSR write from SYSU: csr_type, data. */
class CsrWriteBundle extends Bundle {
  val csr_type = CsrType()
  val data     = UInt(32.W)
}

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
    val csr_write       = Input(Valid(new CsrWriteBundle))
    val commit_rob_id   = Input(UInt(robIdWidth.W))
    val commit_valid    = Input(Bool())
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

  // -------- CSR registers --------
  val csr_mstatus  = RegInit(0x1800.U(32.W))
  val csr_mtvec    = RegInit(0.U(32.W))
  val csr_mepc     = RegInit(0.U(32.W))
  val csr_mcause   = RegInit(0.U(32.W))
  val csr_mscratch = RegInit(0.U(32.W))

  when(io.csr_write.valid && io.csr_write.bits.csr_type =/= CsrType.None) {
    switch(io.csr_write.bits.csr_type) {
      is(CsrType.Mstatus)  { csr_mstatus  := io.csr_write.bits.data }
      is(CsrType.Mtvec)    { csr_mtvec    := io.csr_write.bits.data }
      is(CsrType.Mepc)     { csr_mepc     := io.csr_write.bits.data }
      is(CsrType.Mcause)   { csr_mcause   := io.csr_write.bits.data }
      is(CsrType.Mscratch) { csr_mscratch := io.csr_write.bits.data }
    }
  }

  def readCsr(csrType: CsrType.Type): UInt =
    Mux(csrType === CsrType.None, 0.U(32.W),
      Mux1H(
        Seq(
          csrType === CsrType.Mstatus,
          csrType === CsrType.Mtvec,
          csrType === CsrType.Mepc,
          csrType === CsrType.Mcause,
          csrType === CsrType.Mscratch
        ),
        Seq(csr_mstatus, csr_mtvec, csr_mepc, csr_mcause, csr_mscratch)
      ))

  val csr_type_sysu = Mux(
    io.in.bits.fu_type === FuType.SYSU,
    CsrType.fromAddr(io.in.bits.csr_addr),
    CsrType.None
  )
  val csr_rdata = readCsr(csr_type_sysu)

  io.in.flush := io.out.flush

  val fu_type      = io.in.bits.fu_type
  val fu_src       = io.in.bits.fu_src
  val fu_op        = io.in.bits.fu_op
  val imm          = io.in.bits.imm
  val pc           = io.in.bits.pc
  val rob_id       = io.rob_enq.rob_id
  val can_dispatch = io.in.valid

  val will_csr_write = csr_type_sysu =/= CsrType.None && io.in.bits.csr_will_write
  val pending_csr_write_rob_id = RegInit(0.U(robIdWidth.W))
  val pending_csr_write_valid  = RegInit(false.B)
  when(io.in.flush) {
    pending_csr_write_valid := false.B
  }.elsewhen(io.commit_valid && pending_csr_write_valid && io.commit_rob_id === pending_csr_write_rob_id) {
    pending_csr_write_valid := false.B
  }.elsewhen(can_dispatch && io.out.fire && fu_type === FuType.SYSU && will_csr_write) {
    pending_csr_write_valid := true.B
    pending_csr_write_rob_id := rob_id
  }
  val csr_write_pending_stall = pending_csr_write_valid

  val lsu_can_alloc = Mux(fu_type === FuType.LSU, io.ls_alloc.ready, true.B)
  val can_push      = can_dispatch && !csr_write_pending_stall && io.rob_enq.req.ready && lsu_can_alloc

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
  io.out.bits.csr_addr     := io.in.bits.csr_addr
  io.out.bits.csr_rdata    := csr_rdata
  io.out.bits.rob_id       := rob_id
  io.out.bits.p_rd         := io.in.bits.p_rd
  io.out.bits.old_p_rd     := io.in.bits.old_p_rd
  io.out.bits.rd_index     := io.in.bits.rd_index
  io.out.bits.lsq_id := Mux(fu_type === FuType.LSU, io.ls_alloc.lsq_id, 0.U(lsqIdWidth.W))
  io.out.bits.mem_type := Mux(
    fu_type === FuType.LSU,
    Mux(LsuOp.isLoad(lsuOp), RobMemType.Load, RobMemType.Store),
    RobMemType.None
  )

  io.in.ready := !csr_write_pending_stall && io.rob_enq.req.ready && lsu_can_alloc && io.out.ready
}
