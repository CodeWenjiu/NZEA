package nzea_fpga.boards.lxb_artix7

import java.io.File
import scala.io.Source

object VivadoProject {

  def generate(
      outDir: String,
      part: String,
      xdcPath: String,
      enableILA: Boolean = true
  ): Unit = {
    val svFiles = new File(outDir).listFiles().filter(_.getName.endsWith(".sv")).sorted
    val absOutDir = new File(outDir).getAbsolutePath()
    val tcl = new java.io.PrintWriter(s"$outDir/create_project.tcl")

    val root = { var f = new File(outDir); for (_ <- 1 to 5) f = f.getParentFile; f }
    val prjFile = new File(root, "nzea_fpga/src/boards/lxb_artix7/mig_ddr3/mig_b.prj")
    val prjLines: Seq[String] =
      if (prjFile.exists()) Source.fromFile(prjFile, "UTF-8").getLines().toSeq else Nil

    tcl.println("set script_dir [file dirname [info script]]")
    tcl.println("set prj_root [file normalize [file join $script_dir ../../../../../]]")
    tcl.println("set prj_dir [file join $script_dir ../../.. vp]")
    tcl.println("file mkdir $prj_dir")
    tcl.println("cd $prj_dir")
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

    tcl.println("# ── MIG IP ──")
    tcl.println("set ip_dir [file join $env(TEMP) nzea_mig_ddr3]")
    tcl.println("file delete -force $ip_dir")
    tcl.println("file mkdir $ip_dir")
    tcl.println("")
    tcl.println("# Write mig_b.prj (avoids WSL 9p permission issue)")
    tcl.println("set fd [open [file join $ip_dir mig_b.prj] w]")
    for (line <- prjLines) {
      val escaped = line.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
      tcl.println(s"puts $$fd {$escaped}")
    }
    tcl.println("close $fd")
    tcl.println("")
    tcl.println("file copy -force [file join $prj_root nzea_fpga/src/boards/lxb_artix7/mig_ddr3 mig_ddr3.xci] $ip_dir")
    tcl.println("add_files -norecurse [file join $ip_dir mig_ddr3.xci]")
    tcl.println("set_property -dict [list CONFIG.SIM_BYPASS_INIT_CAL {FAST}] [get_ips mig_ddr3]")
    tcl.println("reset_target all [get_ips mig_ddr3]")
    tcl.println("generate_target all [get_ips mig_ddr3]")
    tcl.println("")

    if (enableILA) {
      val widths = IlaProbes.widths
      tcl.println("# ── ILA IP (matches Chisel BlackBox 'u_ila_0') ──")
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

    tcl.println(s"add_files -norecurse [file join $$prj_root $xdcPath]")
    tcl.println(
      "add_files -fileset constrs_1 -norecurse [file join $prj_root nzea_fpga/src/boards/lxb_artix7/mig_ddr3 mig_ddr3.xdc]"
    )
    svFiles.foreach(f => tcl.println(s"add_files -norecurse [file join $$script_dir ${f.getName}]"))
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
    println(s"Vivado: source $absOutDir/create_project.tcl")
  }

}
