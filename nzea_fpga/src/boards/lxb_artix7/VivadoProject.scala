package nzea_fpga.boards.lxb_artix7

import java.io.File
import scala.io.Source
import scala.sys.process._

object VivadoProject {

  private lazy val isWsl: Boolean = {
    new File("/proc/sys/fs/binfmt_misc/WSLInterop").exists() ||
    (try {
      val v = Source.fromFile("/proc/version").mkString.toLowerCase
      v.contains("microsoft") || v.contains("wsl")
    } catch { case _: Exception => false })
  }

  private def toHostPath(path: String): String =
    if (isWsl) try { s"wslpath -m $path".!!.trim }
    catch { case _: Exception => path }
    else path

  def generate(
      outDir: String,
      part: String,
      xdcPath: String,
      enableILA: Boolean = true
  ): Unit = {
    val svFiles = new File(outDir).listFiles().filter(_.getName.endsWith(".sv")).sorted
    val absOutDir = new File(outDir).getAbsolutePath()
    new File(s"$outDir/vp").mkdirs()
    val tcl = new java.io.PrintWriter(s"$outDir/create_project.tcl")

    tcl.println("set script_dir [file dirname [info script]]")
    tcl.println("set prj_root [file normalize [file join $script_dir ../../../../../]]")
    tcl.println("")
    tcl.println("# ── Stage to TEMP (native Windows path, avoids UNC synthesis failures) ──")
    tcl.println("set prj_dir [file join $env(TEMP) nzea_fpga]")
    tcl.println("catch { file delete -force $prj_dir }")
    tcl.println("file mkdir $prj_dir")
    tcl.println("")
    tcl.println("# Copy SV sources")
    tcl.println("foreach f [glob -nocomplain [file join $script_dir *.sv]] {")
    tcl.println("  file copy -force $f [file join $prj_dir [file tail $f]]")
    tcl.println("}")
    tcl.println("# Copy XDC")
    tcl.println(s"file copy -force [file join $$prj_root $xdcPath] [file join $$prj_dir [file tail $xdcPath]]")
    tcl.println("")
    tcl.println("cd $prj_dir")
    tcl.println("close_project -quiet")
    tcl.println(s"create_project -force nzea_fpga . -part $part")
    tcl.println("")

    tcl.println("# ── Clocking Wizard IP ──")
    tcl.println("create_ip -name clk_wiz -vendor xilinx.com -library ip -module_name clk_wiz_0")
    tcl.println("set_property -dict [list \\")
    tcl.println("  CONFIG.PRIMITIVE {MMCM} \\")
    tcl.println("  CONFIG.PRIM_IN_FREQ {50.000} \\")
    tcl.println("  CONFIG.CLKOUT1_USED {true} \\")
    tcl.println("  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000} \\")
    tcl.println("  CONFIG.CLKOUT2_USED {true} \\")
    tcl.println("  CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {100.000} \\")
    tcl.println("  CONFIG.USE_LOCKED {true} \\")
    tcl.println("  CONFIG.USE_RESET {true} \\")
    tcl.println("] [get_ips clk_wiz_0]")
    tcl.println("generate_target all [get_ips clk_wiz_0]")
    tcl.println("")

    if (enableILA) {
      val widths = IlaProbes.widths
      tcl.println("# ── ILA IP ──")
      tcl.println("create_ip -name ila -vendor xilinx.com -library ip -module_name u_ila_0")
      tcl.println("set_property -dict [list \\")
      tcl.println(s"  CONFIG.C_DATA_DEPTH ${IlaProbes.depth} \\")
      tcl.println(s"  CONFIG.C_NUM_OF_PROBES ${widths.size} \\")
      widths.zipWithIndex.foreach { case (w, i) =>
        tcl.println(s"  CONFIG.C_PROBE${i}_WIDTH $w \\")
      }
      tcl.println("] [get_ips u_ila_0]")
      tcl.println("generate_target all [get_ips u_ila_0]")
      tcl.println("")
    }

    tcl.println("# ── Sources (all from local TEMP copy) ──")
    tcl.println(s"add_files -norecurse [file join $$prj_dir [file tail $xdcPath]]")
    svFiles.foreach(f => tcl.println(s"add_files -norecurse [file join $$prj_dir ${f.getName}]"))
    tcl.println("update_compile_order -fileset sources_1")
    tcl.println("set_property top LxbArtix7Top [current_fileset]")
    tcl.println("")

    tcl.println("# ── Synthesis ──")
    tcl.println("launch_runs synth_1 -jobs 4")
    tcl.println("wait_on_run synth_1")
    tcl.println("")

    tcl.println("# ── Implementation ──")
    tcl.println("launch_runs impl_1 -to_step write_bitstream -jobs 4")
    tcl.println("wait_on_run impl_1")
    tcl.println(s"puts {Bitstream: [glob [file join $$prj_dir nzea_fpga.runs impl_1 LxbArtix7Top.bit]]}")
    tcl.close()
    println(s"\u001b[36mVivado:\u001b[1;33m source ${toHostPath(s"$absOutDir/create_project.tcl")}\u001b[0m")
  }

}
