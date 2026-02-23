package nzea_core

import chisel3._
import chisel3.util.Decoupled
import nzea_config.NzeaConfig

/** Core module: IFU → IDU → ISU → (pipe) → EXU → WBU; bus and GPR write-back. */
class Core(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val ifuBusGen = () => new CoreBusReadOnly(addrWidth, dataWidth)

  val io = IO(new Bundle {
    val bus = ifuBusGen()
  })

  val ifu = Module(new frontend.IFU(ifuBusGen, config.defaultPc))
  val idu = Module(new frontend.IDU(addrWidth))
  val isu = Module(new frontend.ISU(addrWidth))
  val exu = Module(new backend.EXU())
  val wbu = Module(new backend.WBU())

  val if2id = PipelineReg(ifu.io.out)
  if2id <> idu.io.in
  val id2is = PipelineReg(idu.io.out)
  id2is <> isu.io.in

  val is2ex_alu  = PipelineReg(isu.io.alu)
  val is2ex_bru  = PipelineReg(isu.io.bru)
  val is2ex_lsu  = PipelineReg(isu.io.lsu)
  val is2ex_sysu = PipelineReg(isu.io.sysu)
  is2ex_alu  <> exu.io.alu
  is2ex_bru  <> exu.io.bru
  is2ex_lsu  <> exu.io.lsu
  is2ex_sysu <> exu.io.sysu

  val ex2wb = PipelineReg(exu.io.out)
  ex2wb <> wbu.io.in
  idu.io.gpr_wr := wbu.io.gpr_wr

  io.bus <> ifu.io.bus

  // Prevent EXU/WBU (and thus ALU) from being optimized away; they drive gpr_wr and are part of the datapath.
  chisel3.dontTouch(exu.io)
  chisel3.dontTouch(wbu.io)
}
