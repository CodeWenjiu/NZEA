package nzea_core

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_config.NzeaConfig

/** Core module: Rob in Core; FUs write to Rob; Rob sends mem_req to MemUnit; Commit receives Rob commit and commits. */
class Core(implicit config: NzeaConfig) extends Module {
  private val addrWidth  = config.width
  private val robDepth   = config.robDepth
  private val robIdWidth = chisel3.util.log2Ceil(robDepth.max(2))

  val ifu = Module(new frontend.IFU)
  val idu = Module(new frontend.IDU(addrWidth))
  val isu = Module(new frontend.ISU(addrWidth))
  val exu = Module(new backend.EXU(robIdWidth))
  val rob = nzea_core.retire.rob.Rob(robDepth, exu.fuOutputs)
  val commit = Module(new retire.Commit)
  val memUnit = Module(new retire.MemUnit(addrWidth, robIdWidth))

  val io = IO(new Bundle {
    val ibus       = chiselTypeOf(ifu.io.bus)
    val dbus       = chiselTypeOf(memUnit.io.dbus)
    val commit_msg = Output(Valid(new retire.CommitMsg))
  })

  PipelineConnect(ifu.io.out, idu.io.in)
  PipelineConnect(idu.io.out, isu.io.in)
  PipelineConnect(isu.io.alu, exu.io.alu_in)
  PipelineConnect(isu.io.bru, exu.io.bru_in)
  PipelineConnect(isu.io.agu, exu.io.agu_in)
  PipelineConnect(isu.io.sysu, exu.io.sysu_in)

  rob.enq <> isu.io.rob_enq
  rob.io.commit <> commit.io.rob_commit
  rob.io.slotReadRs1 <> isu.io.rob_slot_rs1
  rob.io.slotReadRs2 <> isu.io.rob_slot_rs2
  idu.io.rat_isu_write := isu.io.rat_write
  idu.io.rat_rob_write := rob.io.rat_rob_write
  rob.mem.req <> memUnit.io.req
  rob.mem.resp <> memUnit.io.resp
  idu.io.gpr_wr := rob.io.gpr_wr

  io.ibus       <> ifu.io.bus
  io.dbus       <> memUnit.io.dbus
  io.commit_msg := commit.io.commit_msg
  ifu.io.redirect_pc := commit.io.redirect_pc
}
