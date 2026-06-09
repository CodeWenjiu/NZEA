package nzea_tile

import _root_.circt.stage.ChiselStage
import chisel3._
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig

object TileElaborate {

  /** Tile wrapper: `sim=true` enables DPI bridges; else expose tile IO as top-level ports. */
  class Top(sim: Boolean, platform: SynthPlatform, clockHz: Int = 100_000_000)(implicit config: CoreConfig)
      extends Module {
    override def desiredName = "NzeaTile"

    val tile = Module(new NzeaTile(sim, platform, clockHz))

    if (sim) {
      // DPI mode: platform IO not exposed; tie off
      tile.io := DontCare
    } else {
      val commit_msg = IO(Output(chiselTypeOf(tile.io.asInstanceOf[nzea_tile.platform.HasCommitMsg].commit_msg)))
      commit_msg := tile.io.asInstanceOf[nzea_tile.platform.HasCommitMsg].commit_msg

      platform match {
        case SynthPlatform.Yosys =>
          val io2 = tile.io.asInstanceOf[nzea_tile.platform.yosys.TileIo]
          val devices = IO(chiselTypeOf(io2.yosys_devices))
          devices <> io2.yosys_devices

        case SynthPlatform.HelloFPGA =>
          val io2 = tile.io.asInstanceOf[nzea_tile.platform.hellofpga.TileIo]
          val uart = IO(chiselTypeOf(io2.fpga_uart))
          uart <> io2.fpga_uart
          val fpga_finish = IO(Output(Bool()))
          fpga_finish := io2.fpga_finish
      }
    }

  }

  def elaborate(
      sim: Boolean,
      platform: SynthPlatform,
      outDir: String,
      clockHz: Int = 100_000_000,
      firtoolOpts: Array[String]
  )(implicit config: CoreConfig): Unit = {
    println(
      s"Generating NzeaTile (isa: ${config.isa}, platform: ${platform.segment}, sim: $sim)"
    )
    println(s"Output: $outDir")

    ChiselStage.emitSystemVerilogFile(
      new Top(sim, platform, clockHz),
      args = Array("--target-dir", outDir),
      firtoolOpts = firtoolOpts
    )
  }

}
