#!/usr/bin/env nu
# Yosys synthesis (yosys-sta style)
# Reads build/yosys/*.sv from filelist.f, outputs to build/synth/
#   - Top.netlist.v (netlist)
#   - synth_stat.txt (area/cell report, transistor estimate)
#   - synth_check.txt (check report)
#   - yosys.log (full log)
# Prerequisite: mill nzea_cli.run --synthPlatform yosys (generates build/yosys with Core+exposed IO)

def main [
  hdl_dir?: string
  synth_dir?: string
] {
  let hdl_dir = $hdl_dir | default "build/yosys"
  let synth_dir = $synth_dir | default "build/synth"

  if not ($hdl_dir | path exists) {
    print -e $"Error: ($hdl_dir) not found. Run: mill nzea_cli.run --synthPlatform yosys"
    exit 1
  }

  mkdir $synth_dir

  let script_dir = ($env.FILE_PWD? | default ($env.PWD | path join "scripts"))
  $env.HDL_DIR = $hdl_dir
  $env.SYNTH_DIR = $synth_dir
  $env.DESIGN = "Top"

  yosys -g -l $"($synth_dir)/yosys.log" -c $"($script_dir)/yosys.tcl"
}
