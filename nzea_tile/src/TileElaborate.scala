package nzea_tile

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util.Valid
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig
import nzea_core.retire.CommitMsg

object TileElaborate {

  /** Tile IO bundle exposed at top level for simulation testbenches. */
  class TileTopIO extends Bundle {
    val commit_msg = Output(Valid(new CommitMsg))
    val uart_txd = Output(Bool())
    val uart_rxd = Input(Bool())
    val finish = Output(Bool())
  }

  /** Tile wrapper: `sim=true` enables DPI bridges; else expose tile IO as top-level ports. */
  class Top(sim: Boolean, platform: SynthPlatform, clockHz: Int = 100_000_000)(implicit config: CoreConfig)
      extends Module {
    override def desiredName = "NzeaTile"

    val tile = Module(new NzeaTile(sim, platform, clockHz))
    val io = IO(new TileTopIO)

    if (sim) {
      tile.io := DontCare
      io := DontCare
    } else {
      io.commit_msg := tile.io.asInstanceOf[nzea_tile.platform.HasCommitMsg].commit_msg

      platform match {
        case SynthPlatform.Yosys =>
          val io2 = tile.io.asInstanceOf[nzea_tile.platform.yosys.TileIo]
          val devices = IO(chiselTypeOf(io2.yosys_devices))
          devices <> io2.yosys_devices
          io.uart_txd := DontCare
          io.uart_rxd := DontCare
          io.finish   := DontCare

        case SynthPlatform.HelloFPGA =>
          val io2 = tile.io.asInstanceOf[nzea_tile.platform.hellofpga.TileIo]
          io.uart_txd := io2.fpga_uart.txd
          io2.fpga_uart.rxd := io.uart_rxd
          io2.fpga_uart.ctsn := false.B // tied low (active)
          io.finish := io2.fpga_finish
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
