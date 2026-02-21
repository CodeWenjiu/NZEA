package nzea_core

import chisel3._
import _root_.circt.stage.ChiselStage
import nzea_config.NzeaConfig

object Elaborate {
  def elaborate(implicit config: NzeaConfig): Unit = {
    println(s"Generating NzeaCore (width: ${config.width}, Debug: ${config.debug})")

    ChiselStage.emitSystemVerilogFile(
      new frontend.IFU, // TODO: Replace with new NzeaCore(config) when top-level module supports it
      args = Array("--target-dir", config.outDir),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
  }
}
