package nzea_core

import chisel3._
import _root_.circt.stage.ChiselStage
import nzea_config.NzeaConfig

object Elaborate {
  def elaborate(implicit config: NzeaConfig): Unit = {
    println(s"Generating NzeaCore (width: ${config.width}, Debug: ${config.debug}, platform: ${config.synthPlatform})")
    println(s"Output: ${config.effectiveOutDir}")

    lazy val topModule = new Top
    ChiselStage.emitSystemVerilogFile(
      topModule,
      args = Array("--target-dir", config.effectiveOutDir),
      firtoolOpts = config.platform.firtoolOpts
    )
  }
}
