package nzea_tile

import _root_.circt.stage.ChiselStage
import chisel3._
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig

object TileElaborate {

  /** Tile wrapper: `sim=true` enables DPI bridges; else expose tile IO as top-level ports. */
  class Top(sim: Boolean, platform: SynthPlatform)(implicit config: CoreConfig) extends Module {
    override def desiredName = "NzeaTile"

    val tile = Module(new NzeaTile(sim, platform))
    // Drive inactive platform IO to prevent uninitialized sink errors
    tile.io.yosys_devices := DontCare
    tile.io.fpga_uart     := DontCare

    val boot_override = IO(Input(Bool()))
    tile.io.boot_override := boot_override

    if (sim) {
      // DPI mode: nothing else exposed at Top boundary
    } else {
      val commit_msg = IO(Output(chiselTypeOf(tile.io.commit_msg)))
      commit_msg := tile.io.commit_msg

      platform match {
        case SynthPlatform.Yosys =>
          val devices = IO(chiselTypeOf(tile.io.yosys_devices))
          devices <> tile.io.yosys_devices

        case SynthPlatform.HelloFPGA =>
          val uart = IO(chiselTypeOf(tile.io.fpga_uart))
          uart <> tile.io.fpga_uart
          val fpga_finish = IO(Output(Bool()))
          fpga_finish := tile.io.fpga_finish
      }
    }
  }

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
      new Top(sim, platform),
      args = Array("--target-dir", outDir),
      firtoolOpts = firtoolOpts
    )
  }
}
