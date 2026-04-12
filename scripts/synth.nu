#!/usr/bin/env nu
# Yosys synthesis (yosys-sta style)
# Reads build/<target>/yosys/<isa>/sta/*.sv from filelist.f, outputs to .../sta/synth/
#   - <Top|NzeaTile>.netlist.v (netlist)
#   - synth_stat.txt (area/cell report, transistor estimate)
#   - synth_check.txt (check report)
#   - yosys.log (full log)
# Prerequisite: just dump --sim false --target <core|tile> --isa <isa> (or mill nzea_cli.run ...)

def main [
  --isa: string = "riscv32i"
  --target: string = "core"
  hdl_dir?: string
  synth_dir?: string
] {
  let base_dir = $hdl_dir | default $"build/($target)/yosys/($isa)/sta"
  let hdl_dir = $base_dir
  let synth_dir = $synth_dir | default $"($base_dir)/synth"

  if not ($hdl_dir | path exists) {
    print -e $"Error: ($hdl_dir) not found. Run: just dump --sim false --target ($target) --isa ($isa)"
    exit 1
  }

  mkdir $synth_dir

  let script_dir = ($env.FILE_PWD? | default ($env.PWD | path join "scripts"))
  $env.HDL_DIR = $hdl_dir
  $env.SYNTH_DIR = $synth_dir
  $env.DESIGN = (if $target == "tile" { "NzeaTile" } else { "Top" })

  yosys -g -l $"($synth_dir)/yosys.log" -c $"($script_dir)/yosys.tcl"
}
