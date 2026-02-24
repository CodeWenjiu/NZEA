package nzea_core

import chisel3._
import nzea_config.NzeaConfig

/** Top: Core + ibus/dbus DPI-C bridges. */
class Top(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width

  val core        = Module(new Core)
  val ibusBridge  = Module(new IbusDpiBridge(addrWidth, dataWidth))
  val dbusBridge  = Module(new DbusDpiBridge(addrWidth, dataWidth))

  core.io.ibus <> ibusBridge.io.bus
  core.io.dbus <> dbusBridge.io.bus
}
