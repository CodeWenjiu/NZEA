package nzea_core

import chisel3._
import chisel3.util.Valid
import nzea_config.{FuConfig, NzeaConfig}

/** Core module: Rob in Core; FUs write to [[frontend.Prf]]; Rob sends mem_req to MemUnit; Commit receives Rob commit and commits. */
class Core(implicit config: NzeaConfig) extends Module {
  private val addrWidth    = config.width
  private val robDepth     = config.robDepth
  private val robIdWidth   = chisel3.util.log2Ceil(robDepth.max(2))
  private val prfAddrWidth = config.prfAddrWidth
  private val lsBufferDepth = config.effectiveLsBufferDepth
  private val lsqIdWidth   = config.lsqIdWidth

  val ifu = Module(new frontend.IFU)
  val idu = Module(new frontend.IDU(addrWidth))
  val exu = Module(new backend.EXU(robIdWidth, prfAddrWidth, lsqIdWidth))
  val lsq = Module(new backend.LSQ(robIdWidth, lsBufferDepth, prfAddrWidth))
  val memUnit = Module(new backend.MemUnit(addrWidth, robIdWidth, prfAddrWidth))

  val rob = nzea_core.retire.rob.Rob(robDepth, prfAddrWidth)
  val commit = Module(new retire.Commit)
  val wbu = Module(new retire.WBU(prfAddrWidth))
  val prf  = frontend.Prf.apply(config)
  val isu  = frontend.ISU(addrWidth)
  val iq = Module(new frontend.IssueQueue(robIdWidth, prfAddrWidth, lsqIdWidth, config.iqDepth, FuConfig.numPrfWritePorts))
  val fuOuts = exu.outPorts :+ memUnit.io.out
  (fuOuts zip wbu.io.in).foreach { case (fu, port) => port <> fu }
  (wbu.io.out zip isu.io.prf_write).foreach { case (w, p) => p <> w }
  (wbu.io.out zip iq.io.prf_write).foreach { case (w, p) => p <> w }
  prf.io.write := wbu.io.out
  prf.io.allocClear.valid := isu.io.in.fire && isu.io.in.bits.p_rd =/= 0.U
  prf.io.allocClear.bits  := isu.io.in.bits.p_rd
  (fuOuts zip isu.io.bypass_level1).foreach { case (fu, port) =>
    port.valid := fu.valid
    port.bits := fu.bits
  }
  (fuOuts zip iq.io.bypass_level1).foreach { case (fu, port) =>
    port.valid := fu.valid
    port.bits := fu.bits
  }
  commit.io.do_flush := rob.io.do_flush
  wbu.io.flush := commit.io.do_flush
  isu.io.csr_write := exu.io.csr_write
  (exu.robAccessPorts zip rob.io.accessPorts).foreach { case (fu, port) => port <> fu }

  val io = IO(new Bundle {
    val ibus       = chiselTypeOf(ifu.io.bus)
    val dbus       = chiselTypeOf(memUnit.io.dbus)
    val commit_msg = Output(Valid(new retire.CommitMsg))
  })

  PipelineConnect(ifu.io.out, idu.io.in)
  PipelineConnect(idu.io.out, isu.io.in)
  iq.io.in.valid := isu.io.out.valid
  iq.io.in.bits := isu.io.out.bits
  isu.io.out.ready := iq.io.in.ready
  isu.io.out.flush := iq.io.issuePorts.orderedPorts(0).flush

  prf.io.read(0).addr := isu.io.in.bits.p_rs1
  prf.io.read(1).addr := isu.io.in.bits.p_rs2
  isu.io.prf_enqueue_rs1.data  := prf.io.read(0).data
  isu.io.prf_enqueue_rs1.ready := prf.io.read(0).ready
  isu.io.prf_enqueue_rs2.data  := prf.io.read(1).data
  isu.io.prf_enqueue_rs2.ready := prf.io.read(1).ready

  prf.io.read(2).addr := commit.io.commit_prf_read.addr
  commit.io.commit_prf_read.data := frontend.PrfBypass.mergeCommitData(
    commit.io.commit_prf_read.addr,
    prf.io.read(2).data,
    wbu.io.out
  )

  for (i <- 0 until FuConfig.numIssuePorts; j <- 0 until 2) {
    val r = 3 + i * 2 + j
    prf.io.read(r).addr := iq.io.prf_read(i)(j).addr
    val (d, _) = frontend.PrfBypass.mergeOperand(
      iq.io.prf_read(i)(j).addr,
      prf.io.read(r).data,
      prf.io.read(r).ready,
      isu.io.bypass_level1,
      wbu.io.out
    )
    iq.io.prf_read(i)(j).data := d
  }

  (iq.io.issuePorts.orderedPorts zip exu.io.issuePorts.orderedPorts).foreach { case (a, b) => a <> b }

  rob.enq <> isu.io.rob_enq
  lsq.io.ls_alloc <> isu.io.ls_alloc
  lsq.io.ls_write <> exu.io.agu_ls_write
  rob.io.commit <> commit.io.rob_commit
  isu.io.commit_rob_id := commit.io.commit_rob_id
  isu.io.commit_valid   := commit.io.commit_valid
  rob.io.slotReadRs1.rob_id := 0.U
  rob.io.slotReadRs2.rob_id := 0.U

  idu.io.commit := commit.io.idu_commit
  idu.io.restore_rmt := commit.io.restore_rmt
  lsq.io.issue := rob.mem.issue
  lsq.io.flush := wbu.io.flush
  rob.mem.issue_rob_id := lsq.io.issue_rob_id
  memUnit.io.mem_req.valid := lsq.io.mem_req.valid
  memUnit.io.mem_req.bits := lsq.io.mem_req.bits
  lsq.io.mem_req_ready := memUnit.io.mem_req_ready
  rob.mem.mem_access <> memUnit.io.rob_access

  io.ibus       <> ifu.io.bus
  io.dbus       <> memUnit.io.dbus
  io.commit_msg := commit.io.commit_msg
  ifu.io.redirect_pc := commit.io.redirect_pc
  ifu.io.bp_update := exu.io.bru_bp_update
}
