package nzea_core

import chisel3._
import chisel3.util.Valid
import nzea_config.NzeaConfig

/** Top: `config.sim` = Core + DPI bridges; else Core with ibus/dbus/commit exposed (synthesizable). */
class Top(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width

  val core = Module(new Core)
  if (config.sim) {
    val ib = Module(new nzea_core.dpi.IbusDpiBridge(addrWidth, dataWidth, core.io.ibus.userWidth))
    val db = Module(new nzea_core.dpi.DbusDpiBridge(addrWidth, dataWidth, core.io.dbus.userWidth))
    val cb = Module(new nzea_core.dpi.CommitDpiBridge)
    core.io.ibus     <> ib.io.bus
    core.io.dbus     <> db.io.bus
    cb.io.commit_msg := core.io.commit_msg
  } else {
    val ibus = IO(chiselTypeOf(core.io.ibus))
    val dbus = IO(chiselTypeOf(core.io.dbus))
    val commit_msg = IO(Output(Valid(new retire.CommitMsg)))
    ibus <> core.io.ibus
    dbus <> core.io.dbus
    commit_msg := core.io.commit_msg
  }
}
