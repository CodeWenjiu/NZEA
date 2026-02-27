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
      // Synthesis: expose Core's bus and commit as top-level IO (no DPI)
      val ibus = IO(new CoreBusReadOnly(addrWidth, dataWidth))
      val dbus = IO(new CoreBusReadWrite(addrWidth, dataWidth))
      val commit_msg = IO(Output(new backend.CommitMsg))
      ibus <> core.io.ibus
      dbus <> core.io.dbus
      commit_msg := core.io.commit_msg
    case _ =>
      // Simulation: Core's bus connects to DPI-C bridges
      val ib = Module(new IbusDpiBridge(addrWidth, dataWidth))
      val db = Module(new DbusDpiBridge(addrWidth, dataWidth))
      val cb = Module(new CommitDpiBridge)
      core.io.ibus     <> ib.io.bus
      core.io.dbus     <> db.io.bus
      cb.io.commit_msg := core.io.commit_msg
  }
}
