#!/usr/bin/env bash
# iEDA STA: requires synth first, PDK_PATH (from flake), iEDA in PATH
# Outputs: build/synth/Top.rpt (timing), build/synth/sta.log

set -e
SYNTH_DIR="${1:-build/synth}"
DESIGN="${2:-Top}"
PDK="${3:-icsprout55}"

NETLIST_V="$SYNTH_DIR/${DESIGN}.netlist.v"
SDC_FILE="$(cd "$(dirname "$0")" && pwd)/default.sdc"

if [[ ! -f "$NETLIST_V" ]]; then
  echo "Error: $NETLIST_V not found. Run: just synth" >&2
  exit 1
fi

if [[ -z "$PDK_PATH" ]]; then
  echo "Error: PDK_PATH not set. Use: nix develop .#sta" >&2
  exit 1
fi

if ! command -v iEDA &>/dev/null; then
  echo "Error: iEDA not found. Use: nix develop .#sta" >&2
  exit 1
fi

iEDA -script "$(dirname "$0")/sta.tcl" "$SDC_FILE" "$NETLIST_V" "$DESIGN" "$PDK" 2>&1 | tee "$SYNTH_DIR/sta.log"
