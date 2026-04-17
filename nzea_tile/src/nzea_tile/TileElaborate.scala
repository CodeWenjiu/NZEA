package nzea_tile

import _root_.circt.stage.ChiselStage
import nzea_core.config.CoreConfig

object TileElaborate {
  def elaborate(
    sim: Boolean,
    outDir: String,
    firtoolOpts: Array[String]
  )(implicit config: CoreConfig): Unit = {
    println(
      s"Generating NzeaTile (isa: ${config.isa}, sim: $sim)"
    )
    println(s"Output: $outDir")

    ChiselStage.emitSystemVerilogFile(
      new NzeaTile(sim),
      args = Array("--target-dir", outDir),
      firtoolOpts = firtoolOpts
    )
  }
}
