package nzea_core

import chisel3._
import _root_.circt.stage.ChiselStage
import nzea_config.{ElaborationTarget, NzeaConfig}

object CoreElaborate {
  def elaborate(implicit config: NzeaConfig): Unit = {
    require(
      config.target == ElaborationTarget.Core,
      "Elaborate expects target=core (Top); use --target tile with TileElaborate for NzeaTile"
    )
    println(
      s"Generating Top (target: ${config.target}, isa: ${config.isa}, debug: ${config.debug}, platform: ${config.synthPlatform}, sim: ${config.sim})"
    )
    println(s"Output: ${config.effectiveOutDir}")

    lazy val topModule = new Top
    ChiselStage.emitSystemVerilogFile(
      topModule,
      args = Array("--target-dir", config.effectiveOutDir),
      firtoolOpts = config.firtoolOpts
    )
  }
}
