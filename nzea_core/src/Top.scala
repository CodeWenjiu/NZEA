package nzea_core

import chisel3._
import nzea_config.NzeaConfig

/** Top: Sim = Core + DPI bridges; Yosys = Core with ibus/dbus/commit exposed (synthesizable). */
class Top(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width

  val core = Module(new Core)
  config.platform match {
    case nzea_config.SynthPlatform.Yosys =>
      val ibus = IO(chiselTypeOf(core.io.ibus))
      val dbus = IO(chiselTypeOf(core.io.dbus))
      val commit_msg = IO(Output(new backend.CommitMsg))
      ibus <> core.io.ibus
      dbus <> core.io.dbus
      commit_msg := core.io.commit_msg
    case _ =>
      val ib = Module(new IbusDpiBridge(addrWidth, dataWidth, core.io.ibus.userWidth))
      val db = Module(new DbusDpiBridge(addrWidth, dataWidth, core.io.dbus.userWidth))
      val cb = Module(new CommitDpiBridge)
      core.io.ibus     <> ib.io.bus
      core.io.dbus     <> db.io.bus
      cb.io.commit_msg := core.io.commit_msg
  }
}
