#!/usr/bin/env bash
# Yosys synthesis (yosys-sta style)
# Reads build/yosys/*.sv from filelist.f, outputs to build/synth/
#   - Top.netlist.v (netlist)
#   - synth_stat.txt (area/cell report, transistor estimate)
#   - synth_check.txt (check report)
#   - yosys.log (full log)
# Prerequisite: mill nzea_cli.run --synthPlatform yosys

set -e
HDL_DIR="${1:-build/yosys}"
SYNTH_DIR="${2:-build/synth}"

if [[ ! -d "$HDL_DIR" ]]; then
  echo "Error: $HDL_DIR not found. Run: mill nzea_cli.run --synthPlatform yosys" >&2
  exit 1
fi

mkdir -p "$SYNTH_DIR"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export HDL_DIR SYNTH_DIR DESIGN=Top

yosys -g -l "$SYNTH_DIR/yosys.log" -c "$SCRIPT_DIR/yosys.tcl"
