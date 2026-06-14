package nzea_fpga.boards.lxb_artix7

import java.io.File

object VivadoProject {

  /** Generate Vivado project Tcl script. */
  def generate(outDir: String, part: String, xdcPath: String): Unit = {
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
    // Auto-build + program
    tcl.println("puts \"Running synthesis…\"")
    tcl.println("launch_runs synth_1 -jobs 4")
    tcl.println("wait_on_run synth_1")
    tcl.println("puts \"Running implementation…\"")
    tcl.println("launch_runs impl_1 -to_step write_bitstream -jobs 4")
    tcl.println("wait_on_run impl_1")
    tcl.println("puts \"Bitstream done. Trying to program…\"")
    tcl.println("if {[catch {open_hw} err]} { puts \"No hardware target (board not connected?)\" } else {")
    tcl.println("  if {[catch {")
    tcl.println("    connect_hw_server")
    tcl.println("    open_hw_target")
    tcl.println("    set dev [lindex [get_hw_devices] 0]")
    tcl.println("    set_property PROGRAM.FILE [file join $$prj_dir nzea_fpga.runs impl_1 LxbArtix7Top.bit] $$dev")
    tcl.println("    program_hw_devices $$dev")
    tcl.println("    puts \"Programmed OK.\"")
    tcl.println("  } err2]} { puts \"Auto-program failed (try manual Program Device): $$err2\" }")
    tcl.println("}")
    tcl.close()
    println(s"Vivado project: build/fpga/vp")
    println(s"Source in Vivado Tcl Console:")
    println(s"  source $absOutDir/create_project.tcl")
  }

}
