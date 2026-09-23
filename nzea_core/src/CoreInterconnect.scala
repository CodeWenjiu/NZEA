package nzea_core

import chisel3._
import chisel3.util.Valid
import nzea_rtl.{LiteBusRO, LiteBusRW, PipeIO}
import nzea_core.front.ifu.IbusUser
import nzea_core.isa.LsuOp
import nzea_core.back.{ExecOut, IssueUop}
import nzea_core.front.idu.IduOut
import nzea_config.core.CoreConfig

/** ARF write-back (middle → front): the commit-point retires one architectural register write. */
class ArfWrite extends Bundle {
  val rd_index = UInt(5.W)
  val data = UInt(32.W)
}

/** Core interconnect channels: the existence of every cross-stage channel is decided here, not in the modules.
  * A microarchitecture that drops a channel gates the corresponding factory here and updates the port bundles once.
  */
object CoreChannels {
  def decoded(width: Int)(implicit config: CoreConfig): PipeIO[IduOut] = new PipeIO(new IduOut(width))
  def redirect(width: Int): Valid[UInt] = Valid(UInt(width.W))
  def issue(width: Int)(implicit config: CoreConfig): Valid[IssueUop] = Valid(new IssueUop(width))
  def report(width: Int): ExecOut = new ExecOut(width)
  def commit(implicit config: CoreConfig): Valid[CommitMsg] = Valid(new CommitMsg)
  def arfWrite: Valid[ArfWrite] = Valid(new ArfWrite)
  def ibus(width: Int): LiteBusRO = new LiteBusRO(width, width, IbusUser.userWidth(width), 1)

  def dbus(width: Int): LiteBusRW =
    new LiteBusRW(width, width, CoreTagWidths.dbusUserWidth(width), 1)

}

/** Front-end bus port (module view): decoded stream out, redirect target in, ARF write-back in (commit side). */
class FrontBusPort(width: Int)(implicit config: CoreConfig) extends Bundle {
  val decoded = CoreChannels.decoded(width)
  val redirect = Flipped(CoreChannels.redirect(width))
  val arf_write = Flipped(CoreChannels.arfWrite)
}

/** Middle bus port (module view): decoded in, issue out, report in, redirect out, ARF write-back out, commit out. */
class MiddleBusPort(width: Int)(implicit config: CoreConfig) extends Bundle {
  val decoded = Flipped(CoreChannels.decoded(width))
  val issue = CoreChannels.issue(width)
  val report = Flipped(CoreChannels.report(width))
  val redirect = CoreChannels.redirect(width)
  val arf_write = CoreChannels.arfWrite
  val commit = CoreChannels.commit
}

/** Execute-backend bus port (module view): issue in, report out. */
class BackBusPort(width: Int)(implicit config: CoreConfig) extends Bundle {
  val issue = Flipped(CoreChannels.issue(width))
  val report = CoreChannels.report(width)
}

/** The unified core bus: one bundle carrying every cross-stage channel, three module-view ports. External buses stay
  * on the stage modules as top-level ports (core IO contract, not cross-stage traffic).
  */
class CoreBus(width: Int)(implicit config: CoreConfig) extends Bundle {
  val front = Flipped(new FrontBusPort(width))
  val middle = Flipped(new MiddleBusPort(width))
  val back = Flipped(new BackBusPort(width))
}

/** Stage interconnect: pairs the producer/consumer ends of every channel inside the unified [[CoreBus]]. */
class CoreInterconnect(width: Int)(implicit config: CoreConfig) extends Module {
  val io = IO(new CoreBus(width))

  io.middle.decoded <> io.front.decoded
  io.front.redirect <> io.middle.redirect
  io.back.issue <> io.middle.issue
  io.middle.report <> io.back.report
  io.front.arf_write <> io.middle.arf_write
}
