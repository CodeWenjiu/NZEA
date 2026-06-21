package nzea_fpga.boards.lxb_artix7

import java.io.File
import scala.io.Source

object VivadoProject {

  def generate(
      outDir: String,
      part: String,
      xdcPath: String,
      enableILA: Boolean = true,
      ilaDepth: Int = 4096,
      ilaSignals: Seq[String] =
        Seq("*calib_done*", "*app_rdy* && NAME !~ *data* && NAME !~ *wdf*", "*app_rd_data_valid*", "*app_wdf_rdy*")
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
    tcl.println("reset_target all [get_ips mig_ddr3]")
    tcl.println("generate_target all [get_ips mig_ddr3]")
    tcl.println("")

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

    if (enableILA && ilaSignals.nonEmpty) {
      tcl.println("# ── Insert ILA (post-synthesis netlist) ──")
      tcl.println("open_run synth_1")
      tcl.println("create_debug_core u_ila_0 ila")
      tcl.println(s"set_property C_DATA_DEPTH $ilaDepth [get_debug_cores u_ila_0]")
      tcl.println("set_property C_CLK_INPUT_FREQ_HZ 100000000 [get_debug_cores dbg_hub]")
      tcl.println("set_property C_ENABLE_CLK_DIVIDER true [get_debug_cores dbg_hub]")
      tcl.println("")
      tcl.println("# Find 100 MHz clock net for dbg_hub/clk (_mmcm_clk_out2 in Verilog)")
      tcl.println("set dbg_clk_nets [get_nets -hier -filter {NAME =~ *mmcm*clk_out2* || NAME =~ *clk_out2*}]")
      tcl.println("if {[llength $dbg_clk_nets] == 0} {")
      tcl.println(
        "  set dbg_clk_nets [get_nets -of [get_pins -hier -filter {REF_PIN_NAME == O} -of [get_cells -hier -filter {REF_NAME == BUFG}]]]"
      )
      tcl.println("}")
      tcl.println("if {[llength $dbg_clk_nets] > 0} {")
      tcl.println("  set dbg_clk [lindex $dbg_clk_nets 0]")
      tcl.println("  connect_debug_port dbg_hub/clk $dbg_clk")
      tcl.println("  connect_debug_port u_ila_0/clk $dbg_clk")
      tcl.println("} else {")
      tcl.println("  puts {FATAL: Cannot find clock net for dbg_hub/clk}")
      tcl.println("  exit 1")
      tcl.println("}")
      tcl.println("")
      tcl.println("# Connect probes")
      tcl.println("puts \"Existing probes: [get_debug_ports u_ila_0/probe*]\"")
      tcl.println("")

      ilaSignals.zipWithIndex.foreach { case (pattern, idx) =>
        val label = pattern.split("[*&|]").find(_.nonEmpty).getOrElse(s"sig$idx").take(20)
        if (idx == 0) {
          tcl.println(s"# $label (use probe0 if available)")
          tcl.println(s"set nets [get_nets -hier -filter {NAME =~ $pattern}]")
          tcl.println("if {[llength $nets] > 0} {")
          tcl.println("  set net [lindex $nets 0]")
          tcl.println(s"  puts \"$label net: $$net\"")
          tcl.println("  if {[catch {connect_debug_port u_ila_0/probe0 $net}]} {")
          tcl.println("    create_debug_port u_ila_0 probe")
          tcl.println("    set port [lindex [get_debug_ports u_ila_0/probe*] end]")
          tcl.println("    connect_debug_port $port $net")
          tcl.println("    puts \"  => $port\"")
          tcl.println("  } else { puts \"  => probe0\" }")
          tcl.println(s"} else { puts {WARN: $label not found} }")
        } else {
          tcl.println(s"# $label")
          tcl.println(s"set nets [get_nets -hier -filter {NAME =~ $pattern}]")
          tcl.println("if {[llength $nets] > 0} {")
          tcl.println("  set net [lindex $nets 0]")
          tcl.println("  create_debug_port u_ila_0 probe")
          tcl.println("  set port [lindex [get_debug_ports u_ila_0/probe*] end]")
          tcl.println("  connect_debug_port $port $net")
          tcl.println(s"  puts \"$label => $$port\"")
          tcl.println(s"} else { puts {WARN: $label not found} }")
        }
        tcl.println("")
      }

      tcl.println("puts \"Final probes: [get_debug_ports u_ila_0/probe*]\"")
      tcl.println("write_checkpoint -force [file join $prj_dir post_ila.dcp]")
      tcl.println("")
    }

    tcl.println("# ── Implementation ──")
    tcl.println("launch_runs impl_1 -to_step write_bitstream -jobs 4")
    tcl.println("wait_on_run impl_1")
    tcl.println(s"puts {Bitstream: [glob [file join $$prj_dir nzea_fpga.runs impl_1 LxbArtix7Top.bit]]}")
    tcl.close()
    println(s"Vivado: source $absOutDir/create_project.tcl")
  }

}
