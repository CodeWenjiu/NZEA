package nzea_fpga

import _root_.circt.stage.ChiselStage
import nzea_config.FpgaBoard
import nzea_core.config.CoreConfig
import nzea_fpga.boards.{LxbArtix7Top, TangNano20kTop}

object FpgaElaborate {

  def elaborate(
      board: FpgaBoard,
      outDir: String,
      clockHz: Int = 100_000_000,
      firtoolOpts: Array[String]
  )(implicit config: CoreConfig): Unit = {
    val topName = board match {
      case FpgaBoard.LxbArtix7   => "LxbArtix7Top"
      case FpgaBoard.TangNano20k => "TangNano20kTop"
    }
    println(s"Generating FPGA top ($topName) for board: ${board.segment}")
    println(s"Output: $outDir")

    board match {
      case FpgaBoard.LxbArtix7 =>
        ChiselStage.emitSystemVerilogFile(
          new LxbArtix7Top(clockHz),
          args = Array("--target-dir", outDir),
          firtoolOpts = firtoolOpts
        )
      case FpgaBoard.TangNano20k =>
        ChiselStage.emitSystemVerilogFile(
          new TangNano20kTop(clockHz),
          args = Array("--target-dir", outDir),
          firtoolOpts = firtoolOpts
        )
    }
  }

}
