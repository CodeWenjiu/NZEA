package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.{PipelineConnect, PipeIO}
import nzea_core.frontend.{CsrWriteBundle, IssuePortsBundle, PrfWriteBundle}
import nzea_core.frontend.bp.BpUpdate
import nzea_core.retire.rob.{RobAccessIO, RobMemReq}
import nzea_config.{FuConfig, NzeaConfig}

/** fu_op unified width: max of all FU opcode widths; used by decode/IDU/ISU. */
object FuOpWidth {
  val Width: Int = Seq(AluOp.getWidth, BruOp.getWidth, LsuOp.getWidth, MulOp.getWidth, DivOp.getWidth, SysuOp.getWidth).max
}

/** EXU: receives per-port payloads from ISU (already extracted); direct connect to FUs. */
class EXU(robIdWidth: Int, prfAddrWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val hasM          = config.isaConfig.hasM
  private val numRobPorts    = FuConfig.numRobAccessPorts
  private val numExuPrfPorts = FuConfig.numExuPrfWritePorts

  val alu  = Module(new ALU(robIdWidth, prfAddrWidth))
  val bru  = Module(new BRU(robIdWidth, prfAddrWidth))
  val agu  = Module(new AGU(robIdWidth, prfAddrWidth))
  val sysu = Module(new SYSU(robIdWidth, prfAddrWidth))
  val mul  = Option.when(hasM)(Module(new MUL(robIdWidth, prfAddrWidth)))
  val div  = Option.when(hasM)(Module(new DIV(robIdWidth, prfAddrWidth)))

  val io = IO(new Bundle {
    val issuePorts   = Flipped(new IssuePortsBundle(robIdWidth, prfAddrWidth))
    val flush        = Input(Bool())
    val rob_access   = Vec(numRobPorts, new RobAccessIO(robIdWidth))
    val prf_write    = Vec(numExuPrfPorts, Output(Valid(new PrfWriteBundle(prfAddrWidth))))
    val agu_ls_enq   = Decoupled(new RobMemReq(robIdWidth, prfAddrWidth))
    val csr_write    = Output(Valid(new CsrWriteBundle))
    val bru_bp_update = Output(Valid(new BpUpdate))
  })

  // -------- PipelineConnect + FU connect (unified per FuConfig.issuePorts) --------
  FuConfig.issuePorts(config).foreach { cfg =>
    cfg.name match {
      case "ALU" =>
        val pipeOut = Wire(new PipeIO(new AluInput(robIdWidth, prfAddrWidth)))
        pipeOut.flush := io.flush
        pipeOut.ready := alu.io.in.ready
        PipelineConnect(io.issuePorts.alu, pipeOut)
        alu.io.in.valid := pipeOut.valid
        alu.io.in.bits  := pipeOut.bits
      case "BRU" =>
        val pipeOut = Wire(new PipeIO(new BruInput(robIdWidth, prfAddrWidth)))
        pipeOut.flush := io.flush
        pipeOut.ready := bru.io.in.ready
        PipelineConnect(io.issuePorts.bru, pipeOut)
        bru.io.in.valid := pipeOut.valid
        bru.io.in.bits  := pipeOut.bits
      case "AGU" =>
        val pipeOut = Wire(new PipeIO(new AguInput(robIdWidth, prfAddrWidth)))
        pipeOut.flush := io.flush
        pipeOut.ready := agu.io.in.ready
        PipelineConnect(io.issuePorts.agu, pipeOut)
        agu.io.in.valid := pipeOut.valid
        agu.io.in.bits  := pipeOut.bits
      case "MUL" =>
        mul.foreach { m =>
          val pipeOut = Wire(new PipeIO(new MulInput(robIdWidth, prfAddrWidth)))
          pipeOut.flush := io.flush
          pipeOut.ready := m.io.in.ready
          PipelineConnect(io.issuePorts.mul.get, pipeOut)
          m.io.in.valid := pipeOut.valid
          m.io.in.bits  := pipeOut.bits
        }
      case "DIV" =>
        div.foreach { dm =>
          val pipeOut = Wire(new PipeIO(new DivInput(robIdWidth, prfAddrWidth)))
          pipeOut.flush := io.flush
          pipeOut.ready := dm.io.in.ready
          PipelineConnect(io.issuePorts.div.get, pipeOut)
          dm.io.in.valid := pipeOut.valid
          dm.io.in.bits  := pipeOut.bits
        }
      case "SYSU" =>
        val pipeOut = Wire(new PipeIO(new SysuInput(robIdWidth, prfAddrWidth)))
        pipeOut.flush := io.flush
        pipeOut.ready := sysu.io.in.ready
        PipelineConnect(io.issuePorts.sysu, pipeOut)
        sysu.io.in.valid := pipeOut.valid
        sysu.io.in.bits  := pipeOut.bits
      case _ =>
    }
  }

  io.agu_ls_enq <> agu.io.ls_enq
  io.csr_write := sysu.io.csr_write
  io.bru_bp_update := bru.io.bp_update

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
      case "ALU"  => io.prf_write(i) := alu.io.prf_write
      case "BRU"  => io.prf_write(i) := bru.io.prf_write
      case "SYSU" => io.prf_write(i) := sysu.io.prf_write
      case "MUL"  => mul.foreach(m => io.prf_write(i) := m.io.prf_write)
      case "DIV"  => div.foreach(dm => io.prf_write(i) := dm.io.prf_write)
    }
  }

  def prfWritePorts: Seq[Valid[PrfWriteBundle]] = io.prf_write.toSeq
  def robAccessPorts: Seq[RobAccessIO] = io.rob_access.toSeq
}
