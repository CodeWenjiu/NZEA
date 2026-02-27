package nzea_core

import chisel3._
import nzea_config.NzeaConfig

/** Top: Core + ibus/dbus DPI-C bridges + commit_trace DPI. */
class Top(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width

  val core = Module(new Core)
  config.platform match {
    case nzea_config.SynthPlatform.Yosys =>
      val ib = Module(new IbusSynthBridge(addrWidth, dataWidth))
      val db = Module(new DbusSynthBridge(addrWidth, dataWidth))
      val cb = Module(new CommitSynthBridge)
      core.io.ibus       <> ib.io.bus
      core.io.dbus       <> db.io.bus
      cb.io.commit_msg   := core.io.commit_msg
    case _ =>
      val ib = Module(new IbusDpiBridge(addrWidth, dataWidth))
      val db = Module(new DbusDpiBridge(addrWidth, dataWidth))
      val cb = Module(new CommitDpiBridge)
      core.io.ibus       <> ib.io.bus
      core.io.dbus       <> db.io.bus
      cb.io.commit_msg   := core.io.commit_msg
  }

  /** For Yosys synthesis: expose commit_msg to prevent DCE. */
  val commit_out = config.platform match {
    case nzea_config.SynthPlatform.Yosys => Some(IO(Output(new backend.CommitMsg)))
    case _                               => None
  }
  commit_out.foreach(_ := core.io.commit_msg)
}
