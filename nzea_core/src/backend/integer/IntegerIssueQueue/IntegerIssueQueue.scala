package nzea_core.backend.integer

import chisel3._
import chisel3.util.Valid
import nzea_core.frontend.{IssuePortsBundle, PrfRawRead, PrfReadIO, PrfWriteBundle}
import nzea_config.{CoreConfig, FuConfig}
import nzea_rtl.{PipeIO, PipelineConnect}

/** Integer issue queue: 2-stage pipeline.
  * S1 selects a slot; S2 reads PRF+bypass and dispatches to FU ports.
  * Flush: S2 extracts from issuePorts (consumer); S1 gets it via PipelineConnect from S2 input.
  */
class IntegerIssueQueue(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int, depth: Int)(
  implicit config: CoreConfig
) extends Module {
  private val numPrfWritePorts = FuConfig.numPrfWritePorts
  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
    val prf_write     = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val bypass_level1 = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val commit_rob_id   = Input(UInt(robIdWidth.W))
    val commit_valid    = Input(Bool())
    val issuePorts    = new IssuePortsBundle(robIdWidth, prfAddrWidth, lsqIdWidth)
    val prf_read      = Vec(FuConfig.numIssuePorts, Vec(2, new PrfReadIO(prfAddrWidth)))
    val csr_rdata     = Input(UInt(32.W))
    val csr_read_addr = Output(UInt(12.W))
    /** Enqueue PRF read (rs1/rs2); same ports as [[in]].bits p_rs*. */
    val prf_enqueue_rs1 = Input(new PrfRawRead(prfAddrWidth))
    val prf_enqueue_rs2 = Input(new PrfRawRead(prfAddrWidth))
  })

  val s0 = Module(
    new IntegerIssueQueueSelectStage(
      robIdWidth,
      prfAddrWidth,
      lsqIdWidth,
      depth
    )
  )
  val s1 = Module(new IntegerIssueQueueReadStage(robIdWidth, prfAddrWidth, lsqIdWidth))

  s0.io.in <> io.in
  s0.io.prf_write := io.prf_write
  s0.io.bypass_level1 := io.bypass_level1
  s0.io.commit_rob_id := io.commit_rob_id
  s0.io.commit_valid  := io.commit_valid
  s0.io.wakeup_hints := s1.io.wakeup_hints
  s0.io.prf_enqueue_rs1 := io.prf_enqueue_rs1
  s0.io.prf_enqueue_rs2 := io.prf_enqueue_rs2

  val pipeRegOut = Wire(Vec(FuConfig.numIssuePorts, new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))))
  for (i <- 0 until FuConfig.numIssuePorts) {
    s1.io.in(i).valid := pipeRegOut(i).valid
    s1.io.in(i).bits := pipeRegOut(i).bits
    pipeRegOut(i).ready := s1.io.in(i).ready
    pipeRegOut(i).flush := s1.io.in(i).flush
    PipelineConnect(s0.io.out(i), pipeRegOut(i))
  }
  for (i <- 0 until FuConfig.numIssuePorts; j <- 0 until 2) {
    io.prf_read(i)(j) <> s1.io.prf_read(i)(j)
  }
  s1.io.csr_rdata := io.csr_rdata
  io.csr_read_addr := s1.io.csr_read_addr
  io.issuePorts <> s1.io.issuePorts
}
