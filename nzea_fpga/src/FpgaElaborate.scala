package nzea_fpga

import _root_.circt.stage.ChiselStage
import nzea_config.FpgaBoard
import nzea_core.config.CoreConfig
import nzea_fpga.boards.lxb_artix7.LxbArtix7Top
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

  /** Generate Vivado project Tcl script for Xilinx boards. */
  private def generateVivadoProject(outDir: String, part: String, xdcPath: String): Unit = {
    val svFiles = new File(outDir).listFiles().filter(_.getName.endsWith(".sv")).sorted
    val absOutDir = new File(outDir).getAbsolutePath()
    val tcl = new java.io.PrintWriter(s"$outDir/create_project.tcl")
    tcl.println(s"set script_dir [file dirname [info script]]")
    tcl.println(s"set prj_root [file normalize [file join $$script_dir ../../../../../]]")
    // Short path to avoid Vivado 260-char limit: go up 3 levels to fpga/, create vp/
    tcl.println(s"set prj_dir [file join $$script_dir ../../.. vp]")
    tcl.println(s"file mkdir $$prj_dir")
    tcl.println(s"cd $$prj_dir")
    tcl.println(s"create_project -force nzea_fpga . -part $part")
    tcl.println(s"add_files -norecurse [file join $$prj_root $xdcPath]")
    svFiles.foreach(f => tcl.println(s"add_files -norecurse [file join $$script_dir ${f.getName}]"))
    tcl.println("update_compile_order -fileset sources_1")
    // Simulation
    tcl.println(s"add_files -fileset sim_1 -norecurse [file join $$prj_root nzea_fpga/src/boards/lxb_artix7/tb_lxb_artix7.sv]")
    tcl.println(s"set_property top tb_lxb_artix7 [get_filesets sim_1]")
    tcl.println(s"set_property top_lib xil_defaultlib [get_filesets sim_1]")
    tcl.close()
    println(s"Vivado project: build/fpga/vp")
    println(s"Source in Vivado Tcl Console:")
    println(s"  source $absOutDir/create_project.tcl")
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
          new LxbArtix7Top(clockHz = 50_000_000),
          args = Array("--target-dir", outDir),
          firtoolOpts = firtoolOpts
        )
        generateVivadoProject(outDir, "xc7a200tsbg484-1",
          "nzea_fpga/src/boards/lxb_artix7/A7_lite.xdc")
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
