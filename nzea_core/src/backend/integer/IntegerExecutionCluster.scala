package nzea_core.backend.integer

import chisel3._
import chisel3.util.Valid
import nzea_core.{PipelineConnect, PipeIO}
import nzea_core.frontend.{IssuePortsBundle, PrfWriteBundle}
import nzea_core.frontend.bp.BpUpdate
import nzea_core.retire.rob.{LsWriteReq, RobEntryStateUpdate}
import nzea_config.{FuConfig, NzeaConfig}

/** Integer execution cluster: ALU, BRU, AGU, MUL/DIV, SYSU; receives per-port payloads from [[IntegerIssueQueue]]. */
class IntegerExecutionCluster(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int)(implicit config: NzeaConfig)
    extends Module {
  private val hasM           = config.isaConfig.hasM
  private val numRobPorts    = FuConfig.numRobAccessPorts
  private val numExuPrfPorts = FuConfig.numExuPrfWritePorts

  val alu  = Module(new ALU(robIdWidth, prfAddrWidth))
  val bru  = Module(new BRU(robIdWidth, prfAddrWidth))
  val agu  = Module(new AGU(robIdWidth, prfAddrWidth, lsqIdWidth))
  val sysu = Module(new SYSU(robIdWidth, prfAddrWidth))
  val mul  = Option.when(hasM)(Module(new MUL(robIdWidth, prfAddrWidth)))
  val div  = Option.when(hasM)(Module(new DIV(robIdWidth, prfAddrWidth)))

  val io = IO(new Bundle {
    val issuePorts    = Flipped(new IssuePortsBundle(robIdWidth, prfAddrWidth, lsqIdWidth))
    val rob_access    = Vec(numRobPorts, Output(Valid(new RobEntryStateUpdate(robIdWidth))))
    val out           = Vec(numExuPrfPorts, new PipeIO(new PrfWriteBundle(prfAddrWidth)))
    val agu_ls_write  = new PipeIO(new LsWriteReq(lsqIdWidth))
    val bru_bp_update = Output(Valid(new BpUpdate))
  })

  FuConfig.issuePorts(config).foreach { cfg =>
    cfg.name match {
      case "ALU" =>
        val pipeOut = Wire(new PipeIO(new AluInput(robIdWidth, prfAddrWidth)))
        pipeOut.flush := alu.io.in.flush
        pipeOut.ready := alu.io.in.ready
        PipelineConnect(io.issuePorts.alu, pipeOut)
        alu.io.in.valid := pipeOut.valid
        alu.io.in.bits  := pipeOut.bits
      case "BRU" =>
        val pipeOut = Wire(new PipeIO(new BruInput(robIdWidth, prfAddrWidth)))
        pipeOut.flush := bru.io.in.flush
        pipeOut.ready := bru.io.in.ready
        PipelineConnect(io.issuePorts.bru, pipeOut)
        bru.io.in.valid := pipeOut.valid
        bru.io.in.bits  := pipeOut.bits
      case "AGU" =>
        val pipeOut = Wire(new PipeIO(new AguInput(robIdWidth, prfAddrWidth, lsqIdWidth)))
        pipeOut.flush := io.agu_ls_write.flush
        pipeOut.ready := agu.io.in.ready
        PipelineConnect(io.issuePorts.agu, pipeOut)
        agu.io.in.valid := pipeOut.valid
        agu.io.in.bits  := pipeOut.bits
      case "MUL" =>
        mul.foreach { m =>
          val pipeOut = Wire(new PipeIO(new MulInput(robIdWidth, prfAddrWidth)))
          pipeOut.flush := m.io.in.flush
          pipeOut.ready := m.io.in.ready
          PipelineConnect(io.issuePorts.mul.get, pipeOut)
          m.io.in.valid := pipeOut.valid
          m.io.in.bits  := pipeOut.bits
        }
      case "DIV" =>
        div.foreach { dm =>
          val pipeOut = Wire(new PipeIO(new DivInput(robIdWidth, prfAddrWidth)))
          pipeOut.flush := dm.io.in.flush
          pipeOut.ready := dm.io.in.ready
          PipelineConnect(io.issuePorts.div.get, pipeOut)
          dm.io.in.valid := pipeOut.valid
          dm.io.in.bits  := pipeOut.bits
        }
      case "SYSU" =>
        val pipeOut = Wire(new PipeIO(new SysuInput(robIdWidth, prfAddrWidth)))
        pipeOut.flush := sysu.io.in.flush
        pipeOut.ready := sysu.io.in.ready
        PipelineConnect(io.issuePorts.sysu, pipeOut)
        sysu.io.in.valid := pipeOut.valid
        sysu.io.in.bits  := pipeOut.bits
      case _ =>
    }
  }

  io.agu_ls_write.valid := agu.io.ls_write.valid
  io.agu_ls_write.bits  := agu.io.ls_write.bits
  agu.io.ls_write.ready := io.agu_ls_write.ready
  agu.io.ls_write.flush := io.agu_ls_write.flush
  io.bru_bp_update      := bru.io.bp_update

  FuConfig.robAccessPorts(config).zipWithIndex.foreach { case (cfg, i) =>
    cfg.name match {
      case "ALU"  => io.rob_access(i) <> alu.io.rob_access
      case "BRU"  => io.rob_access(i) <> bru.io.rob_access
      case "SYSU" => io.rob_access(i) <> sysu.io.rob_access
      case "MUL"  => mul.foreach(m => io.rob_access(i) <> m.io.rob_access)
      case "DIV"  => div.foreach(dm => io.rob_access(i) <> dm.io.rob_access)
      case "AGU"  => io.rob_access(i) <> agu.io.rob_access
    }
  }

  FuConfig.exuPrfWritePorts(config).zipWithIndex.foreach { case (cfg, i) =>
    cfg.name match {
      case "ALU"  => io.out(i) <> alu.io.out
      case "BRU"  => io.out(i) <> bru.io.out
      case "SYSU" => io.out(i) <> sysu.io.out
      case "MUL"  => mul.foreach(m => io.out(i) <> m.io.out)
      case "DIV"  => div.foreach(dm => io.out(i) <> dm.io.out)
    }
  }

  def outPorts: Seq[PipeIO[PrfWriteBundle]]              = io.out.toSeq
  def robAccessPorts: Seq[Valid[RobEntryStateUpdate]] = io.rob_access.toSeq
}
