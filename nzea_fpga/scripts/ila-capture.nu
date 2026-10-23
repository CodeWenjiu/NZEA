#!/usr/bin/env nu

# Capture an ILA window to VCD on a programmed board, then rename the probes to their semantic names for pulse.
#
# Drives the dump-generated `ila_capture.tcl` (written by Ila.emit beside the RTL) inside the batch Vivado FHS
# environment, then runs `probe vcd-rename` with the map the same emission wrote.
#
# Usage:
#   nu nzea_fpga/scripts/ila-capture.nu [--board lxb_artix7] [--isa riscv32i] [--trigger '<probe>[=<value>]']

const chips_script = (path self . | path join "chips.nu")
const batch_nix = (path self . | path join "vivado-batch.nix")
source $chips_script

def main [
    --board: string = "lxb_artix7"
    --isa: string = "riscv32i"
    --trigger: string = ""   # empty: trigger immediately; '<probe>[=<value>]': arm on that probe's compare value
] {
    let info = board_info $board
    let rtl_dir = fpga_rtl_dir $board $isa
    let vivado_dir = $"($rtl_dir)/vivado"
    let tcl = $"($rtl_dir)/ila_capture.tcl"
    if not ($tcl | path exists) {
        print $"ERROR: ($tcl) missing — this RTL was dumped without the ILA"
        print "Re-dump and rebuild with just fpga build, then program."
        exit 1
    }

    let what = if $trigger == "" { "immediate trigger" } else { $"armed on ($trigger)" }
    print $"Capturing ILA ($what)…"
    let script = $"cd ($vivado_dir)
vivado -mode batch -source ($tcl) -nolog -nojournal -tclargs ($info.part) '($trigger)'
exit
"
    $script | ^nix-shell $batch_nix

    let vcd = $"($vivado_dir)/capture.vcd"
    if not ($vcd | path exists) {
        print "ERROR: no capture.vcd produced by the ILA capture"
        exit 1
    }
    let named = $"($vivado_dir)/capture.named.vcd"
    let map = $"($rtl_dir)/ila_probes.map"
    # The rename map and the capture script come from the same emission.
    cargo run --release --quiet -p probe_cli -- vcd-rename $map $vcd $named
    print $"ILA capture ready for pulse: ($named)"
}
