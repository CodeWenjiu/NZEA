package nzea_core

import chisel3._
import chisel3.util.Decoupled
import nzea_config.NzeaConfig

/** Core module: IFU → IDU → ISU → (pipe) → EXU → WBU; bus and GPR write-back. Rob inside WBU. */
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

  val if2id = PipelineReg(ifu.io.out)
  if2id <> idu.io.in
  val id2is = PipelineReg(idu.io.out)
  id2is <> isu.io.in

  val is2ex_alu  = PipelineReg(isu.io.alu)
  val is2ex_bru  = PipelineReg(isu.io.bru)
  val is2ex_agu  = PipelineReg(isu.io.agu)
  val is2ex_sysu = PipelineReg(isu.io.sysu)

  isu.io.rob_pending_rd := wbu.io.rob_pending_rd
  isu.io.wb_bypass      := wbu.io.wb_bypass
  wbu.io.rob_enq <> isu.io.rob_enq

  is2ex_alu  <> exu.io.alu
  is2ex_bru  <> exu.io.bru
  is2ex_agu  <> exu.io.agu
  is2ex_sysu <> exu.io.sysu

  val ex2wb_alu  = PipelineReg(exu.io.alu_out)
  val ex2wb_bru  = PipelineReg(exu.io.bru_out)
  val ex2wb_agu  = PipelineReg(exu.io.agu_out)
  val ex2wb_sysu = PipelineReg(exu.io.sysu_out)
  ex2wb_alu  <> wbu.io.alu_in
  ex2wb_bru  <> wbu.io.bru_in
  ex2wb_agu  <> wbu.io.agu_in
  ex2wb_sysu <> wbu.io.sysu_in
  idu.io.gpr_wr := wbu.io.gpr_wr

  io.ibus       <> ifu.io.bus
  io.dbus       <> wbu.io.dbus
  io.commit_msg := wbu.io.commit_msg
  ifu.io.pc_redirect := exu.io.pc_redirect
}
