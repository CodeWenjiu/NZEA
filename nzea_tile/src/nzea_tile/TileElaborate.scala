package nzea_tile

import _root_.circt.stage.ChiselStage
import nzea_config.{ElaborationTarget, NzeaConfig}

object TileElaborate {
  def elaborate(implicit config: NzeaConfig): Unit = {
    require(
      config.target == ElaborationTarget.Tile,
      "TileElaborate expects target=tile; use --target core for Top"
    )
    println(
      s"Generating NzeaTile (target: ${config.target}, isa: ${config.isa}, debug: ${config.debug}, platform: ${config.synthPlatform})"
    )
    println(s"Output: ${config.effectiveOutDir}")

    ChiselStage.emitSystemVerilogFile(
      new NzeaTile,
      args = Array("--target-dir", config.effectiveOutDir),
      firtoolOpts = config.platform.firtoolOpts
    )
  }
}
