#!/usr/bin/env nu
# Yosys synthesis of a dumped design: `filelist.f` beside the RTL in, `<design>.netlist.v`, synth_stat.txt,
# synth_check.txt and yosys.log into the dump's `synth/` (dump first: `just dump --flow yosys --target <core|tile>`).

def main [
  --isa: string = "riscv32i"
  --target: string = "core"
  --platform: string = "yosys"
  hdl_dir?: string
  synth_dir?: string
] {
  let base_dir = $hdl_dir | default $"build/($target)/($platform)/($isa)"
  let hdl_dir = $base_dir
  let synth_dir = $synth_dir | default $"($base_dir)/synth"

  if not ($hdl_dir | path exists) {
    print -e $"Error: ($hdl_dir) not found. Run: just dump --flow yosys --target ($target) --isa ($isa)"
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
