package nzea_core

import chisel3._
import nzea_config.NzeaConfig

/** Top: Core + ibus/dbus DPI-C bridges + commit_trace DPI. */
class Top(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width

  val core          = Module(new Core)
  val ibusBridge    = Module(new IbusDpiBridge(addrWidth, dataWidth))
  val dbusBridge    = Module(new DbusDpiBridge(addrWidth, dataWidth))
  val commitBridge  = Module(new CommitDpiBridge)

  core.io.ibus       <> ibusBridge.io.bus
  core.io.dbus       <> dbusBridge.io.bus
  commitBridge.io.commit_msg := core.io.commit_msg
}
