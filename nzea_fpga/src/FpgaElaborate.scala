package nzea_fpga

import _root_.circt.stage.ChiselStage
import nzea_config.FpgaBoard
import nzea_core.config.CoreConfig
import nzea_fpga.boards.lxb_artix7.{LxbArtix7Top, VivadoProject}
import nzea_fpga.boards.tangnano20k.TangNano20kTop

import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}

object FpgaElaborate {

  /** Gowin synthesis has a built-in ALU primitive; rename our ALU module to avoid conflict. */
  private def renameAluForGowin(outDir: String): Unit = {
    val dir = new File(outDir)
    val aluFile = new File(dir, "ALU.sv")
    if (!aluFile.exists()) return

    val newFile = new File(dir, "ALU_nzea.sv")
    Files.move(aluFile.toPath, newFile.toPath, StandardCopyOption.REPLACE_EXISTING)

    val modPattern = raw"\bmodule ALU\b"
    val instPattern = raw"\bALU alu\b"

    for (f <- dir.listFiles((_, n) => n.endsWith(".sv"))) {
      val content = new String(Files.readAllBytes(f.toPath))
      val updated = content
        .replaceAll(modPattern, "module ALU_nzea")
        .replaceAll(instPattern, "ALU_nzea alu")
      if (updated != content)
        Files.write(f.toPath, updated.getBytes)
    }
  }

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
          new LxbArtix7Top(clockHz = 100_000_000),
          args = Array("--target-dir", outDir),
          firtoolOpts = firtoolOpts
        )
        // No separate test top — Ddr3TestCore is instantiated directly in LxbArtix7Top
        VivadoProject.generate(outDir, "xc7a200tsbg484-1", "nzea_fpga/src/boards/lxb_artix7/A7_lite.xdc")
      case FpgaBoard.TangNano20k =>
        ChiselStage.emitSystemVerilogFile(
          new TangNano20kTop(clockHz),
          args = Array("--target-dir", outDir),
          firtoolOpts = firtoolOpts
        )
        // Gowin-specific: rename ALU module to avoid name collision with built-in primitive
        renameAluForGowin(outDir)
    }
  }

}
