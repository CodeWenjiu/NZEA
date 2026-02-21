package nzea_core

import chisel3._
import _root_.circt.stage.ChiselStage
import mainargs.arg

case class NzeaConfig(
  @arg(short = 'w', doc = "CPU core data width") width: Int = 32,
  @arg(short = 'd', doc = "Whether to enable Debug port") debug: Boolean = false,
  @arg(short = 'o', doc = "Verilog output directory") outDir: String = "build/nzea"
)

object Elaborate {
  def elaborate(config: NzeaConfig): Unit = {
    println(s"Generating NzeaCore (width: ${config.width}, Debug: ${config.debug})")

    ChiselStage.emitSystemVerilogFile(
      new GCD, // TODO: Replace with new NzeaCore(config) when top-level module supports it
      args = Array("--target-dir", config.outDir),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
  }
}
