package nzea_core.frontend

import chisel3._
import nzea_rtl.PipeIO
import nzea_core.backend.integer.{AguInput, AluInput, BruInput, DivInput, IssuePortLayout, MulInput, SysuInput}
import nzea_core.backend.integer.nnu.NnInput
import nzea_config.core.CoreConfig
import nzea_config.core.FuKind

/** Per-port payload types: each issue port has FU-specific input (AluInput, BruInput, etc.).
  * Operand extraction (e.g. ALU opA/opB from fu_src) happens in ISU before pipeline reg.
  */
class IssuePortsBundle(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int)(implicit config: CoreConfig) extends Bundle {
  private val layout = IssuePortLayout.build(config)

  val alu  = new PipeIO(new AluInput(robIdWidth, prfAddrWidth))
  val bru  = new PipeIO(new BruInput(robIdWidth, prfAddrWidth))
  val agu  = new PipeIO(new AguInput(robIdWidth, prfAddrWidth, lsqIdWidth))
  val mul  = if (config.isaConfig.hasM) Some(new PipeIO(new MulInput(robIdWidth, prfAddrWidth))) else None
  val div  = if (config.isaConfig.hasM) Some(new PipeIO(new DivInput(robIdWidth, prfAddrWidth))) else None
  val nnu  =
    if (config.isaConfig.hasWjcus0) Some(new PipeIO(new NnInput(robIdWidth, prfAddrWidth))) else None
  val sysu = new PipeIO(new SysuInput(robIdWidth, prfAddrWidth))

  /** Ports in FuConfig.issuePorts order for iteration. */
  def orderedPorts: Seq[PipeIO[_ <: Bundle]] = {
    def byKind(kind: FuKind): PipeIO[_ <: Bundle] = kind match {
      case FuKind.Alu  => alu
      case FuKind.Bru  => bru
      case FuKind.Agu  => agu
      case FuKind.Mul  => mul.get
      case FuKind.Div  => div.get
      case FuKind.Nnu  => nnu.get
      case FuKind.Sysu => sysu
    }
    layout.kinds.map(byKind)
  }
}
