package nzea_tile

import _root_.circt.stage.ChiselStage
import nzea_config.{CoreConfig, ElaborationTarget, NzeaConfig}

object TileElaborate {
  def elaborate(implicit config: NzeaConfig): Unit = {
    require(
      config.target == ElaborationTarget.Tile,
      "TileElaborate expects target=tile; use --target core for Top"
    )
    implicit val coreConfig: CoreConfig = config.core
    println(
      s"Generating NzeaTile (target: ${config.target}, isa: ${config.core.isa}, debug: ${config.debug}, platform: ${config.synthPlatform}, sim: ${config.sim})"
    )
    println(s"Output: ${config.effectiveOutDir}")

    ChiselStage.emitSystemVerilogFile(
      new NzeaTile(config.sim),
      args = Array("--target-dir", config.effectiveOutDir),
      firtoolOpts = config.firtoolOpts
    )
  }
}
