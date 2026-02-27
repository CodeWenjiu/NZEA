#!/usr/bin/env nu
# iEDA STA: requires synth first, PDK_PATH (from flake), iEDA in PATH
# Outputs: build/synth/Top.rpt (timing), build/synth/sta.log

def main [
  synth_dir?: string
  design?: string
  pdk?: string
] {
  let synth_dir = $synth_dir | default "build/synth"
  let design = $design | default "Top"
  let pdk = $pdk | default "icsprout55"

  let script_dir = ($env.FILE_PWD? | default ($env.PWD | path join "scripts"))
  let netlist_v = $synth_dir | path join $"($design).netlist.v"
  let sdc_file = $script_dir | path join "default.sdc"

  if not ($netlist_v | path exists) {
    print -e $"Error: ($netlist_v) not found. Run: just synth"
    exit 1
  }

  if ($env.PDK_PATH? | is-empty) or ($env.PDK_PATH == "") {
    print -e "Error: PDK_PATH not set. Use: nix develop .#sta"
    exit 1
  }

  if ((which iEDA | length) == 0) {
    print -e "Error: iEDA not found. Use: nix develop .#sta"
    exit 1
  }

  let sta_log = $synth_dir | path join "sta.log"
  # iEDA writes to both stdout and stderr; use tee to save and print
  ^sh -c $"iEDA -script ($script_dir)/sta.tcl ($sdc_file) ($netlist_v) ($design) ($pdk) 2>&1 | tee ($sta_log)"
}
