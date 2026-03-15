package nzea_core

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_config.NzeaConfig

/** Core module: Rob in Core; FUs write to Rob and PRF (in ISU); Rob sends mem_req to MemUnit; Commit receives Rob commit and commits. */
class Core(implicit config: NzeaConfig) extends Module {
  private val addrWidth    = config.width
  private val robDepth     = config.robDepth
  private val robIdWidth   = chisel3.util.log2Ceil(robDepth.max(2))
  private val prfAddrWidth = config.prfAddrWidth

  val ifu = Module(new frontend.IFU)
  val idu = Module(new frontend.IDU(addrWidth))
  val exu = Module(new backend.EXU(robIdWidth, prfAddrWidth))
  private val lsBufferDepth = (robDepth / 2).max(1)
  val memUnit = Module(new retire.MemUnit(addrWidth, robIdWidth, lsBufferDepth, prfAddrWidth))

  val rob = nzea_core.retire.rob.Rob(robDepth, prfAddrWidth)
  val commit = Module(new retire.Commit)
  val wbu = Module(new retire.WBU(prfAddrWidth))
  val isu = frontend.ISU(addrWidth)
  val fuPrfWrites = exu.prfWritePorts :+ memUnit.io.prf_write
  (fuPrfWrites zip wbu.io.in).foreach { case (fu, port) => port <> fu }
  (wbu.io.out zip isu.io.prf_write).foreach { case (w, p) => p <> w }
  (fuPrfWrites zip isu.io.bypass_level1).foreach { case (fu, port) =>
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
  (isu.io.issuePorts.orderedPorts zip exu.io.issuePorts.orderedPorts).foreach { case (a, b) => a <> b }

  rob.enq <> isu.io.rob_enq
  memUnit.io.ls_enq <> exu.io.agu_ls_enq
  rob.io.commit <> commit.io.rob_commit
  isu.io.prf_read_addr := commit.io.prf_read_addr
  isu.io.commit_rob_id := commit.io.commit_rob_id
  isu.io.commit_valid   := commit.io.commit_valid
  commit.io.prf_rd_value := isu.io.prf_read_data
  rob.io.slotReadRs1.rob_id := 0.U
  rob.io.slotReadRs2.rob_id := 0.U


  idu.io.commit := commit.io.idu_commit
  idu.io.flush := rob.io.do_flush
  idu.io.restore_rmt := commit.io.restore_rmt
  memUnit.io.issue := rob.mem.issue
  rob.mem.issue_rob_id := memUnit.io.issue_rob_id
  memUnit.io.flush := rob.mem.flush
  rob.mem.resp <> memUnit.io.resp

  io.ibus       <> ifu.io.bus
  io.dbus       <> memUnit.io.dbus
  io.commit_msg := commit.io.commit_msg
  ifu.io.redirect_pc := commit.io.redirect_pc
  ifu.io.bp_update := exu.io.bru_bp_update
}
