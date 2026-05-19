#!/usr/bin/env nu
# Yosys synthesis (yosys-sta style)
# Reads build/<target>/<platform>/<isa>/hw/*.sv from filelist.f, outputs to .../hw/synth/
#   - <NzeaCore|NzeaTile>.netlist.v (netlist)
#   - synth_stat.txt (area/cell report, transistor estimate)
#   - synth_check.txt (check report)
#   - yosys.log (full log)
# Prerequisite: just dump --sim false --target <core|tile> --isa <isa> (or mill nzea_cli.run ...)

def main [
  --isa: string = "riscv32i"
  --target: string = "core"
  --platform: string = "yosys"
  hdl_dir?: string
  synth_dir?: string
] {
  let base_dir = $hdl_dir | default $"build/($target)/($platform)/($isa)/hw"
  let hdl_dir = $base_dir
  let synth_dir = $synth_dir | default $"($base_dir)/synth"

  if not ($hdl_dir | path exists) {
    print -e $"Error: ($hdl_dir) not found. Run: just dump --sim false --target ($target) --isa ($isa)"
    exit 1
  }

  mkdir $synth_dir

  let script_dir = ($env.FILE_PWD? | default ($env.PWD | path join "synth"))
  let yosys_dir = $script_dir | path join ".." "yosys"
  $env.HDL_DIR = $hdl_dir
  $env.SYNTH_DIR = $synth_dir
  $env.PLATFORM = $platform
  $env.DESIGN = (if $target == "tile" { "NzeaTile" } else { "NzeaCore" })

  yosys -g -l $"($synth_dir)/yosys.log" -c $"($yosys_dir)/yosys.tcl"
}
