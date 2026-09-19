package nzea_core.min

import chisel3._
import chisel3.util.Valid
import nzea_rtl.{LiteBusRW, PipeIO}
import nzea_core.frontend.IbusUser
import nzea_core.backend.integer.LsuOp
import nzea_core.retire.CommitMsg
import nzea_core.min.back.{MinExecOut, MinIssueUop}
import nzea_core.min.front.MinIduOut
import nzea_config.core.CoreConfig

/** Core interconnect channels: the existence of every cross-stage channel (front↔middle↔back) is decided here, not in
  * the modules or in MinCore.
  *
  * All units are baseline constants today — the in-order engine needs every channel. A future microarchitecture that
  * drops a channel (e.g. no branch redirect) gates the corresponding factory here (Option shape) and updates the port
  * bundles once; the wiring in [[CoreInterconnect]] and [[MinCore]] stays untouched.
  */
object CoreChannels {
  def decoded(width: Int)(implicit config: CoreConfig): PipeIO[MinIduOut] = new PipeIO(new MinIduOut(width))
  def redirect(width: Int): Valid[UInt] = Valid(UInt(width.W))
  def issue(width: Int)(implicit config: CoreConfig): Valid[MinIssueUop] = Valid(new MinIssueUop(width))
  def report(width: Int): MinExecOut = new MinExecOut(width)
  def commit(implicit config: CoreConfig): Valid[CommitMsg] = Valid(new CommitMsg)
  def ibus(width: Int): LiteBusRW = new LiteBusRW(width, width, IbusUser.userWidth(width), 1)

  def dbus(width: Int, robIdWidth: Int, prfAddrWidth: Int): LiteBusRW =
    new LiteBusRW(width, width, width.max(robIdWidth + LsuOp.getWidth + 2 + prfAddrWidth + 1), 1)

}

/** Front-end bus port (module view): decoded stream out, redirect target in. */
class FrontBusPort(width: Int)(implicit config: CoreConfig) extends Bundle {
  val decoded = CoreChannels.decoded(width)
  val redirect = Flipped(CoreChannels.redirect(width))
}

/** Middle bus port (module view): decoded in, issue out, report in, redirect out, commit out. */
class MiddleBusPort(width: Int)(implicit config: CoreConfig) extends Bundle {
  val decoded = Flipped(CoreChannels.decoded(width))
  val issue = CoreChannels.issue(width)
  val report = Flipped(CoreChannels.report(width))
  val redirect = CoreChannels.redirect(width)
  val commit = CoreChannels.commit
}

/** Execute-backend bus port (module view): issue in, report out. */
class BackBusPort(width: Int, robIdWidth: Int, prfAddrWidth: Int)(implicit config: CoreConfig) extends Bundle {
  val issue = Flipped(CoreChannels.issue(width))
  val report = CoreChannels.report(width)
}

/** The unified core bus: one bundle carrying every cross-stage channel, three module-view ports.
  *
  * Which channels exist is decided by [[CoreChannels]]; [[CoreInterconnect]] pairs the producer/consumer ends, and
  * MinCore only mounts the three stages — it never sees individual stage wires. The external buses (ibus/dbus) stay on
  * the stage modules as top-level ports (they are the core IO contract, not cross-stage traffic).
  */
class CoreBus(width: Int)(implicit config: CoreConfig) extends Bundle {
  private val robIdWidth = chisel3.util.log2Ceil(config.robDepth.max(2))

  // Sub-ports are the module-view bundles flipped: the interconnect sits opposite the stages, so `<>` pairs ends.
  val front = Flipped(new FrontBusPort(width))
  val middle = Flipped(new MiddleBusPort(width))
  val back = Flipped(new BackBusPort(width, robIdWidth, config.prfAddrWidth))
}

/** Stage interconnect: pairs the producer/consumer ends of every channel inside the unified [[CoreBus]]. External buses
  * (ibus via front, dbus via back, commit via middle) pass through unpaired — MinCore lifts them to the core IO
  * contract.
  */
class CoreInterconnect(width: Int)(implicit config: CoreConfig) extends Module {
  val io = IO(new CoreBus(width))

  io.middle.decoded <> io.front.decoded
  io.front.redirect <> io.middle.redirect
  io.back.issue <> io.middle.issue
  io.middle.report <> io.back.report
}
