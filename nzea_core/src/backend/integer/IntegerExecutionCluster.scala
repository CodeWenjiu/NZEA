package nzea_core.backend.integer

import chisel3._
import chisel3.util.Valid
import nzea_rtl.{PipelineConnect, PipeIO}
import nzea_core.frontend.{IssuePortsBundle, PrfWriteBundle}
import nzea_core.frontend.bp.BpUpdate
import nzea_core.retire.rob.{LsWriteReq, RobEntryStateUpdate}
import nzea_config.core.CoreConfig
import nzea_config.core.FuConfig
import nzea_config.core.FuKind
import nzea_core.backend.integer.nnu.NNU

/** Integer execution cluster: ALU, BRU, AGU, MUL/DIV, NNU (WJCUS0), SYSU; receives per-port payloads from [[IntegerIssueQueue]]. */
class IntegerExecutionCluster(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int)(implicit config: CoreConfig)
    extends Module {
  private val hasM           = config.isaConfig.hasM
  private val hasNn          = config.isaConfig.hasWjcus0
  private val numRobPorts    = FuConfig.numRobAccessPorts
  private val numExuPrfPorts = FuConfig.numExuPrfWritePorts

  val alu  = Module(new ALU(robIdWidth, prfAddrWidth))
  val bru  = Module(new BRU(robIdWidth, prfAddrWidth))
  val agu  = Module(new AGU(robIdWidth, prfAddrWidth, lsqIdWidth))
  val sysu = Module(new SYSU(robIdWidth, prfAddrWidth))
  val mul  = Option.when(hasM)(Module(new MUL(robIdWidth, prfAddrWidth)))
  val div  = Option.when(hasM)(Module(new DIV(robIdWidth, prfAddrWidth)))
  val nnu  = Option.when(hasNn)(Module(new NNU(robIdWidth, prfAddrWidth)))

  val io = IO(new Bundle {
    val issuePorts    = Flipped(new IssuePortsBundle(robIdWidth, prfAddrWidth, lsqIdWidth))
    val rob_access    = Vec(numRobPorts, Output(Valid(new RobEntryStateUpdate(robIdWidth))))
    val out           = Vec(numExuPrfPorts, new PipeIO(new PrfWriteBundle(prfAddrWidth)))
    val agu_ls_write  = new PipeIO(new LsWriteReq(lsqIdWidth))
    val bru_bp_update = Output(Valid(new BpUpdate))
  })

  private def connectIssuePipe[T <: Bundle](src: PipeIO[T], dst: PipeIO[T], flush: Bool, ready: Bool): Unit = {
    val pipeOut = Wire(chiselTypeOf(dst))
    pipeOut.flush := flush
    pipeOut.ready := ready
    PipelineConnect(src, pipeOut)
    dst.valid := pipeOut.valid
    dst.bits  := pipeOut.bits
  }

  private case class FuWiring(
    connectIssue: () => Unit,
    connectRobAccess: Int => Unit,
    connectPrfOut: Option[Int => Unit]
  )

  private val wiringByKind: Map[FuKind, FuWiring] = Map(
    FuKind.Alu -> FuWiring(
      connectIssue = () => connectIssuePipe(io.issuePorts.alu, alu.io.in, alu.io.in.flush, alu.io.in.ready),
      connectRobAccess = i => io.rob_access(i) <> alu.io.rob_access,
      connectPrfOut = Some(i => io.out(i) <> alu.io.out)
    ),
    FuKind.Bru -> FuWiring(
      connectIssue = () => connectIssuePipe(io.issuePorts.bru, bru.io.in, bru.io.in.flush, bru.io.in.ready),
      connectRobAccess = i => io.rob_access(i) <> bru.io.rob_access,
      connectPrfOut = Some(i => io.out(i) <> bru.io.out)
    ),
    FuKind.Agu -> FuWiring(
      connectIssue = () => connectIssuePipe(io.issuePorts.agu, agu.io.in, io.agu_ls_write.flush, agu.io.in.ready),
      connectRobAccess = i => io.rob_access(i) <> agu.io.rob_access,
      connectPrfOut = None
    ),
    FuKind.Mul -> FuWiring(
      connectIssue = () =>
        mul.foreach { m =>
          io.issuePorts.mul.foreach { p =>
            connectIssuePipe(p, m.io.in, m.io.in.flush, m.io.in.ready)
          }
        },
      connectRobAccess = i => mul.foreach(m => io.rob_access(i) <> m.io.rob_access),
      connectPrfOut = Some(i => mul.foreach(m => io.out(i) <> m.io.out))
    ),
    FuKind.Div -> FuWiring(
      connectIssue = () =>
        div.foreach { dm =>
          io.issuePorts.div.foreach { p =>
            connectIssuePipe(p, dm.io.in, dm.io.in.flush, dm.io.in.ready)
          }
        },
      connectRobAccess = i => div.foreach(dm => io.rob_access(i) <> dm.io.rob_access),
      connectPrfOut = Some(i => div.foreach(dm => io.out(i) <> dm.io.out))
    ),
    FuKind.Sysu -> FuWiring(
      connectIssue = () => connectIssuePipe(io.issuePorts.sysu, sysu.io.in, sysu.io.in.flush, sysu.io.in.ready),
      connectRobAccess = i => io.rob_access(i) <> sysu.io.rob_access,
      connectPrfOut = Some(i => io.out(i) <> sysu.io.out)
    ),
    FuKind.Nnu -> FuWiring(
      connectIssue = () =>
        nnu.foreach { nn =>
          io.issuePorts.nnu.foreach { p =>
            connectIssuePipe(p, nn.io.in, nn.io.in.flush, nn.io.in.ready)
          }
        },
      connectRobAccess = i => nnu.foreach(nn => io.rob_access(i) <> nn.io.rob_access),
      connectPrfOut = Some(i => nnu.foreach(nn => io.out(i) <> nn.io.out))
    )
  )

  FuConfig.issuePorts(config).foreach { cfg =>
    wiringByKind(cfg.kind).connectIssue()
  }

  io.agu_ls_write.valid := agu.io.ls_write.valid
  io.agu_ls_write.bits  := agu.io.ls_write.bits
  agu.io.ls_write.ready := io.agu_ls_write.ready
  agu.io.ls_write.flush := io.agu_ls_write.flush
  io.bru_bp_update      := bru.io.bp_update

  FuConfig.robAccessPorts(config).zipWithIndex.foreach { case (cfg, i) =>
    wiringByKind(cfg.kind).connectRobAccess(i)
  }

  FuConfig.exuPrfWritePorts(config).zipWithIndex.foreach { case (kind, i) =>
    wiringByKind(kind).connectPrfOut.foreach(_(i))
  }

  def outPorts: Seq[PipeIO[PrfWriteBundle]]              = io.out.toSeq
  def robAccessPorts: Seq[Valid[RobEntryStateUpdate]] = io.rob_access.toSeq
}
