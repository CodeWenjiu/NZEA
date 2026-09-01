package nzea_fpga

import _root_.circt.stage.ChiselStage
import nzea_config.FpgaBoard
import nzea_config.core.CoreConfig
import nzea_fpga.boards.lxb_artix7.{IlaProbes, LxbArtix7Config, LxbArtix7Top}
import nzea_fpga.boards.tangnano20k.TangNano20kTop

import java.io.File
import java.nio.file.{Files, StandardCopyOption}
import scala.sys.process._

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

  /** Generate the Vivado project via the shared `vivado-project.nu` entry point.
    *
    * All board-specific parameters (part, top module, XDC, IP snippets) are resolved
    * from `chips.nu` inside the script; this call only passes the board segment and ISA.
    * This keeps `just dump` and `just vivado-project` on the same code path.
    *
    * When the freshly generated top-level RTL instantiates the ILA wrapper, a small
    * `ila_config.json` metadata file is written into the RTL directory so the ILA IP
    * TCL snippet can be generated with the exact probe widths from
    * [[IlaProbes.widths]] (the single source of truth). When the wrapper is not
    * referenced, a stale `ila_config.json` from a previous build is removed.
    */
  private def generateVivadoProject(board: FpgaBoard, isa: String, outDir: String): Unit = {
    // Write ILA metadata if the top-level RTL actually instantiates the ILA wrapper
    // (firtool only emits an `IlaProbes` instance in the top when the wrapper is
    // referenced). Checking the freshly generated top file — rather than the
    // presence of a standalone IlaProbes.sv — avoids mis-detecting a stale
    // IlaProbes.sv left over from a previous build after the wrapper was removed.
    val topFile = new File(outDir, s"${topNameFor(board)}.sv")
    val hasIla = topFile.exists() &&
      new String(Files.readAllBytes(topFile.toPath)).contains("IlaProbes")
    if (hasIla) {
      val json =
        s"""{"depth":${IlaProbes.depth},"widths":[${IlaProbes.widths.mkString(",")}]}"""
      Files.write(new File(outDir, "ila_config.json").toPath, json.getBytes)
    } else {
      new File(outDir, "ila_config.json").delete()
    }

    val script = new File("nzea_fpga/scripts/vivado-project.nu").getAbsolutePath
    val cmd = Seq("nu", script, "--board", board.segment, "--isa", isa)
    val proc = Process(cmd, new File("."))
    proc.!< match {
      case 0 => ()
      case code =>
        Console.err.println(s"[FpgaElaborate] vivado-project generation failed (exit $code)")
    }
  }

  /** Board top module name (mirrors the elaborate() match). */
  private def topNameFor(board: FpgaBoard): String = board match {
    case FpgaBoard.LxbArtix7   => "LxbArtix7Top"
    case FpgaBoard.TangNano20k => "TangNano20kTop"
  }

  def elaborate(
      board: FpgaBoard,
      outDir: String,
      clockHz: Int,
      firtoolOpts: Array[String]
  )(implicit config: CoreConfig): Unit = {
    val topName = topNameFor(board)
    println(s"Generating FPGA top ($topName) for board: ${board.segment}")
    println(s"Output: $outDir")

    board match {
      case FpgaBoard.LxbArtix7 =>
        ChiselStage.emitSystemVerilogFile(
          new LxbArtix7Top(clockHz),
          args = Array("--target-dir", outDir),
          firtoolOpts = firtoolOpts
        )
        // Generate the Vivado project (edalize-based) right after the RTL so the
        // user gets the paste-able full-flow command from a single dump invocation.
        generateVivadoProject(board, config.isa, outDir)
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
