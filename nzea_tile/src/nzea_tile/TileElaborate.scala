package nzea_tile

import _root_.circt.stage.ChiselStage
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig

object TileElaborate {
  def elaborate(
    sim: Boolean,
    platform: SynthPlatform,
    outDir: String,
    firtoolOpts: Array[String]
  )(implicit config: CoreConfig): Unit = {
    println(
      s"Generating NzeaTile (isa: ${config.isa}, platform: ${platform.segment}, sim: $sim)"
    )
    println(s"Output: $outDir")

    ChiselStage.emitSystemVerilogFile(
      new NzeaTile(sim, platform),
      args = Array("--target-dir", outDir),
      firtoolOpts = firtoolOpts
    )
  }
}
