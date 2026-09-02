#!/usr/bin/env bash
# Run the Vivado build and/or programming flow headlessly.
#
# Drives the generated `build_all.tcl` (synthesis + implementation + bitstream)
# and/or `program.tcl` (JTAG programming) inside the batch Vivado FHS
# environment (vivado-batch.nix). Run from the repo root.
#
# Usage:
#   nzea_fpga/scripts/vivado-flow.sh [--board lxb_artix7] [--isa riscv32im]
#                                    [--build-only | --program-only]
set -euo pipefail

BOARD="lxb_artix7"
ISA="riscv32im"
MODE="all"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --board) BOARD="$2"; shift 2 ;;
    --isa) ISA="$2"; shift 2 ;;
    --build-only) MODE="build"; shift ;;
    --program-only) MODE="program"; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BATCH_NIX="$SCRIPT_DIR/vivado-batch.nix"
VIVADO_DIR="$PWD/build/fpga/$BOARD/$ISA/hw/vivado"

if [[ ! -f "$BATCH_NIX" ]]; then
  echo "ERROR: batch FHS env not found: $BATCH_NIX" >&2
  exit 1
fi
if [[ ! -d "$VIVADO_DIR" ]]; then
  echo "ERROR: Vivado project not found at $VIVADO_DIR" >&2
  echo "Run: just dump --target fpga --fpgaBoard $BOARD --isa $ISA --sim false" >&2
  exit 1
fi

run_vivado_tcl() {
  local tcl="$1"
  if [[ ! -f "$VIVADO_DIR/$tcl" ]]; then
    echo "ERROR: $tcl not found in $VIVADO_DIR" >&2
    exit 1
  fi
  echo "Running Vivado: $tcl (in $VIVADO_DIR)"
  # nix-shell consumes commands from stdin (the FHS runScript is an interactive
  # bash; --run/--command are unreliable there), so pipe a heredoc through it.
  nix-shell "$BATCH_NIX" <<NIXEOF
cd "$VIVADO_DIR"
vivado -mode batch -source "$tcl" -nolog -nojournal
NIXEOF
}

if [[ "$MODE" == "all" || "$MODE" == "build" ]]; then
  run_vivado_tcl build_all.tcl
fi
if [[ "$MODE" == "all" || "$MODE" == "program" ]]; then
  run_vivado_tcl program.tcl
fi

echo "Vivado flow finished."
