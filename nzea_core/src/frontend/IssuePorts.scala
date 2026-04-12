package nzea_core.frontend

import chisel3._
import nzea_rtl.PipeIO
import nzea_core.backend.integer.{AguInput, AluInput, BruInput, DivInput, MulInput, SysuInput}
import nzea_core.backend.integer.nnu.NnInput
import nzea_config.{FuConfig, NzeaConfig}

/** Per-port payload types: each issue port has FU-specific input (AluInput, BruInput, etc.).
  * Operand extraction (e.g. ALU opA/opB from fu_src) happens in ISU before pipeline reg.
  */
class IssuePortsBundle(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int)(implicit config: NzeaConfig) extends Bundle {
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
    val base = Seq(alu, bru, agu)
    val md   = if (config.isaConfig.hasM) Seq(mul.get, div.get) else Seq.empty
    val nn   = if (config.isaConfig.hasWjcus0) Seq(nnu.get) else Seq.empty
    base ++ md ++ nn :+ sysu
  }
}
