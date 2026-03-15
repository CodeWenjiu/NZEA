#!/usr/bin/env nu
# Yosys synthesis (yosys-sta style)
# Reads build/yosys/<isa>/*.sv from filelist.f, outputs to build/yosys/<isa>/synth/
#   - Top.netlist.v (netlist)
#   - synth_stat.txt (area/cell report, transistor estimate)
#   - synth_check.txt (check report)
#   - yosys.log (full log)
# Prerequisite: just dump --synthPlatform yosys --isa <isa> (generates build/yosys/<isa>)

def main [
  --isa: string = "riscv32i"
  hdl_dir?: string
  synth_dir?: string
] {
  let base_dir = $hdl_dir | default $"build/yosys/($isa)"
  let hdl_dir = $base_dir
  let synth_dir = $synth_dir | default $"($base_dir)/synth"

  if not ($hdl_dir | path exists) {
    print -e $"Error: ($hdl_dir) not found. Run: just dump --synthPlatform yosys --isa ($isa)"
    exit 1
  }

  mkdir $synth_dir

  let script_dir = ($env.FILE_PWD? | default ($env.PWD | path join "scripts"))
  $env.HDL_DIR = $hdl_dir
  $env.SYNTH_DIR = $synth_dir
  $env.DESIGN = "Top"

  yosys -g -l $"($synth_dir)/yosys.log" -c $"($script_dir)/yosys.tcl"
}
