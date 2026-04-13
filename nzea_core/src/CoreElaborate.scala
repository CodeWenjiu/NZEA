package nzea_core

import chisel3._
import chisel3.util.Valid
import _root_.circt.stage.ChiselStage
import nzea_config.{CoreConfig, ElaborationTarget, NzeaConfig}

object CoreElaborate {

  /** Core wrapper: `sim=true` enables DPI bridges; else expose ibus/dbus/commit as top-level IO. */
  class Top(sim: Boolean)(implicit config: CoreConfig) extends Module {
    override def desiredName = "Top"

    private val addrWidth = config.width
    private val dataWidth = config.width

    val core = Module(new Core)
    if (sim) {
      val ib = Module(new nzea_core.dpi.IbusDpiBridge(addrWidth, dataWidth, core.io.ibus.userWidth))
      val db = Module(new nzea_core.dpi.DbusDpiBridge(addrWidth, dataWidth, core.io.dbus.userWidth))
      val cb = Module(new nzea_core.dpi.CommitDpiBridge)
      core.io.ibus <> ib.io.bus
      core.io.dbus <> db.io.bus
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

  def elaborate(implicit config: NzeaConfig): Unit = {
    require(
      config.target == ElaborationTarget.Core,
      "Elaborate expects target=core (Top); use --target tile with TileElaborate for NzeaTile"
    )
    implicit val coreConfig: CoreConfig = config.core
    println(
      s"Generating Top (target: ${config.target}, isa: ${config.core.isa}, debug: ${config.debug}, platform: ${config.synthPlatform}, sim: ${config.sim})"
    )
    println(s"Output: ${config.effectiveOutDir}")

    lazy val topModule = new Top(config.sim)
    ChiselStage.emitSystemVerilogFile(
      topModule,
      args = Array("--target-dir", config.effectiveOutDir),
      firtoolOpts = config.firtoolOpts
    )
  }
}
