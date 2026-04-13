package nzea_core

import chisel3._
import chisel3.util.Valid
import nzea_core.frontend.CsrType
import nzea_config.{FuConfig, CoreConfig}
import nzea_rtl.PipelineConnect

/** Core module: Rob in Core; integer cluster + LSU write to [[frontend.Prf]] / [[frontend.CsrFile]]; Commit. */
class Core(implicit config: CoreConfig) extends Module {
  private val addrWidth    = config.width
  private val robDepth     = config.robDepth
  private val robIdWidth   = chisel3.util.log2Ceil(robDepth.max(2))
  private val prfAddrWidth = config.prfAddrWidth
  private val lsBufferDepth = config.effectiveLsBufferDepth
  private val lsqIdWidth   = config.lsqIdWidth

  val ifu = Module(new frontend.IFU)
  val idu = Module(new frontend.IDU(addrWidth))
  val integerIssueQueue       = Module(new backend.integer.IntegerIssueQueue(robIdWidth, prfAddrWidth, lsqIdWidth, config.iqDepth))
  val integerExecutionCluster = Module(new backend.integer.IntegerExecutionCluster(robIdWidth, prfAddrWidth, lsqIdWidth))
  val lsu = Module(new backend.LSU(addrWidth, robIdWidth, lsBufferDepth, prfAddrWidth))

  val rob = nzea_core.retire.rob.Rob(robDepth, prfAddrWidth)
  val commit = Module(new retire.Commit)
  val wbu = Module(new retire.WBU(prfAddrWidth))
  val prf = frontend.Prf.apply(config)
  val csr = Module(new frontend.CsrFile())
  val isu = frontend.ISU(addrWidth)
  val fuOuts = integerExecutionCluster.outPorts :+ lsu.io.out
  (fuOuts zip wbu.io.in).foreach { case (fu, port) => port <> fu }
  (wbu.io.out zip integerIssueQueue.io.prf_write).foreach { case (w, p) => p <> w }
  prf.io.write := wbu.io.out
  // Clear PRF ready when the renamed uop leaves the IDU→ISU pipe reg (isu.in.fire), not idu.out.fire:
  // PipelineConnect registers one cycle between idu.out and isu.in; idu.out.fire is one cycle earlier.
  // When ISU is removed (IDU→issue queue directly): switch to integerIssueQueue.io.in.fire (or the downstream PipeIO fire that
  // replaces isu.in), not idu.io.out.fire, unless IDU and issue queue are combinatorially connected with no reg.
  prf.io.allocClear.valid := isu.io.in.fire && isu.io.in.bits.p_rd =/= 0.U
  prf.io.allocClear.bits  := isu.io.in.bits.p_rd
  (fuOuts zip integerIssueQueue.io.bypass_level1).foreach { case (fu, port) =>
    port.valid := fu.valid
    port.bits := fu.bits
  }
  commit.io.do_flush := rob.io.do_flush
  wbu.io.flush := commit.io.do_flush
  // CSR updates only on ROB commit (not on SYSU execute), so squashed uops never modify CSRs.
  val csrWriteCommit = Wire(Valid(new frontend.CsrWriteBundle))
  csrWriteCommit.valid := rob.io.commit.valid && rob.io.commit.bits.csr_type =/= CsrType.None
  csrWriteCommit.bits.csr_type := rob.io.commit.bits.csr_type
  csrWriteCommit.bits.data := rob.io.commit.bits.csr_data
  csr.io.csr_write := csrWriteCommit
  (integerExecutionCluster.robAccessPorts zip rob.io.accessPorts).foreach { case (fu, port) => port <> fu }

  val io = IO(new Bundle {
    val ibus       = chiselTypeOf(ifu.io.bus)
    val dbus       = chiselTypeOf(lsu.io.dbus)
    val commit_msg = Output(Valid(new retire.CommitMsg))
  })

  PipelineConnect(ifu.io.out, idu.io.in)
  PipelineConnect(idu.io.out, isu.io.in)
  integerIssueQueue.io.in.valid := isu.io.out.valid
  integerIssueQueue.io.in.bits := isu.io.out.bits
  isu.io.out.ready := integerIssueQueue.io.in.ready
  isu.io.out.flush := integerIssueQueue.io.issuePorts.orderedPorts(0).flush

  prf.io.read(0).addr := integerIssueQueue.io.in.bits.p_rs1
  prf.io.read(1).addr := integerIssueQueue.io.in.bits.p_rs2
  integerIssueQueue.io.prf_enqueue_rs1.data  := prf.io.read(0).data
  integerIssueQueue.io.prf_enqueue_rs1.ready := prf.io.read(0).ready
  integerIssueQueue.io.prf_enqueue_rs2.data  := prf.io.read(1).data
  integerIssueQueue.io.prf_enqueue_rs2.ready := prf.io.read(1).ready

  prf.io.read(2).addr := commit.io.commit_prf_read.addr
  commit.io.commit_prf_read.data := frontend.PrfBypass.mergeCommitData(
    commit.io.commit_prf_read.addr,
    prf.io.read(2).data,
    wbu.io.out
  )

  for (i <- 0 until FuConfig.numIssuePorts; j <- 0 until 2) {
    val r = 3 + i * 2 + j
    prf.io.read(r).addr := integerIssueQueue.io.prf_read(i)(j).addr
    val (d, _) = frontend.PrfBypass.mergeOperand(
      integerIssueQueue.io.prf_read(i)(j).addr,
      prf.io.read(r).data,
      prf.io.read(r).ready,
      integerIssueQueue.io.bypass_level1,
      wbu.io.out
    )
    integerIssueQueue.io.prf_read(i)(j).data := d
  }

  csr.io.read_addr := integerIssueQueue.io.csr_read_addr
  integerIssueQueue.io.csr_rdata := csr.io.read_data

  integerIssueQueue.io.issuePorts <> integerExecutionCluster.io.issuePorts

  rob.enq <> isu.io.rob_enq
  lsu.io.ls_alloc <> isu.io.ls_alloc
  lsu.io.agu_ls_write <> integerExecutionCluster.io.agu_ls_write
  rob.io.commit <> commit.io.rob_commit
  integerIssueQueue.io.commit_rob_id := commit.io.commit_rob_id
  integerIssueQueue.io.commit_valid  := commit.io.commit_valid
  rob.io.slotReadRs1.rob_id := 0.U
  rob.io.slotReadRs2.rob_id := 0.U

  idu.io.commit := commit.io.idu_commit
  idu.io.restore_rmt := commit.io.restore_rmt
  lsu.io.issue := rob.mem.issue
  lsu.io.flush := rob.mem.flush
  rob.mem.issue_rob_id := lsu.io.issue_rob_id
  rob.mem.mem_access <> lsu.io.rob_access

  io.ibus       <> ifu.io.bus
  io.dbus       <> lsu.io.dbus
  io.commit_msg := commit.io.commit_msg
  ifu.io.redirect_pc := commit.io.redirect_pc
  ifu.io.bp_update := integerExecutionCluster.io.bru_bp_update
}
