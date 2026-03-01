package nzea_core

import chisel3._
import chisel3.util.Decoupled
import nzea_config.NzeaConfig

/** Core module: IFU → IDU → ISU → (pipe) → EXU → WBU; bus and GPR write-back. Rob inside WBU.
  * Flush: WBU drives on alu_in/bru_in etc.; propagates forward via <> and PipelineConnect.
  */
class Core(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width

  val ifu = Module(new frontend.IFU)
  val idu = Module(new frontend.IDU(addrWidth))
  val isu = Module(new frontend.ISU(addrWidth))
  val exu = Module(new backend.EXU)
  val wbu = Module(new backend.WBU)

  val io = IO(new Bundle {
    val ibus       = chiselTypeOf(ifu.io.bus)
    val dbus       = chiselTypeOf(wbu.io.dbus)
    val commit_msg = Output(new backend.CommitMsg)
  })

  PipelineConnect(exu.io.alu_out, wbu.io.alu_in)
  PipelineConnect(exu.io.bru_out, wbu.io.bru_in)
  PipelineConnect(exu.io.agu_out, wbu.io.agu_in)
  PipelineConnect(exu.io.sysu_out, wbu.io.sysu_in)

  PipelineConnect(isu.io.alu, exu.io.alu_in)
  PipelineConnect(isu.io.bru, exu.io.bru_in)
  PipelineConnect(isu.io.agu, exu.io.agu_in)
  PipelineConnect(isu.io.sysu, exu.io.sysu_in)

  PipelineConnect(idu.io.out, isu.io.in)
  PipelineConnect(ifu.io.out, idu.io.in)

  isu.io.rob_pending_rd := wbu.io.rob_pending_rd
  isu.io.wb_bypass      := wbu.io.wb_bypass
  wbu.io.rob_enq <> isu.io.rob_enq
  idu.io.gpr_wr := wbu.io.gpr_wr

  io.ibus       <> ifu.io.bus
  io.dbus       <> wbu.io.dbus
  io.commit_msg := wbu.io.commit_msg
  ifu.io.redirect_pc := wbu.io.redirect_pc
}
