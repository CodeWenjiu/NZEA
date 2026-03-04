package nzea_core

import chisel3._
import chisel3.util.Decoupled
import nzea_config.NzeaConfig

/** Core module: Rob in Core; FUs write to Rob; Rob sends mem_req to MemUnit; WBU receives Rob commit and commits. */
class Core(implicit config: NzeaConfig) extends Module {
  private val addrWidth  = config.width
  private val robDepth   = config.robDepth
  private val robIdWidth = chisel3.util.log2Ceil(robDepth.max(2))
  private val dbusType   = new CoreBusReadWrite(config.width, config.width, config.width)

  val ifu = Module(new frontend.IFU)
  val idu = Module(new frontend.IDU(addrWidth))
  val isu = Module(new frontend.ISU(addrWidth))
  val exu = Module(new backend.EXU(robIdWidth))
  val wbu = Module(new retire.WBU)
  val fuOutputs = Seq(
    exu.io.alu_rob_access,
    exu.io.bru_rob_access,
    exu.io.sysu_rob_access,
    exu.io.agu_rob_access
  )
  val rob = Module(new nzea_core.retire.rob.Rob(robDepth, numAccessPorts = fuOutputs.size))
  val memUnit = Module(new retire.MemUnit(dbusType, robIdWidth))

  rob.connectFuOutputs(fuOutputs)

  val io = IO(new Bundle {
    val ibus       = chiselTypeOf(ifu.io.bus)
    val dbus       = dbusType.cloneType
    val commit_msg = Output(new retire.CommitMsg)
  })

  rob.enq.req <> isu.io.rob_enq
  isu.io.rob_enq_rob_id := rob.enq.rob_id
  rob.rat.req := isu.io.rat_req
  isu.io.rat_resp := rob.rat.resp

  rob.io.commit <> wbu.io.rob_commit
  rob.mem.req <> memUnit.io.req
  rob.mem.resp <> memUnit.io.resp

  PipelineConnect(isu.io.alu, exu.io.alu_in)
  PipelineConnect(isu.io.bru, exu.io.bru_in)
  PipelineConnect(isu.io.agu, exu.io.agu_in)
  PipelineConnect(isu.io.sysu, exu.io.sysu_in)

  PipelineConnect(idu.io.out, isu.io.in)
  PipelineConnect(ifu.io.out, idu.io.in)

  idu.io.gpr_wr := wbu.io.gpr_wr

  val do_flush = rob.io.commit.fire && rob.io.commit.bits.flush
  io.ibus       <> ifu.io.bus
  io.dbus       <> memUnit.io.dbus
  io.commit_msg := wbu.io.commit_msg
  ifu.io.redirect_pc := wbu.io.redirect_pc
}
