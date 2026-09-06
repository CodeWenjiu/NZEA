#!/usr/bin/env nu

# Capture an ILA window to VCD on the lxb_artix7 board, then rename the probes
# to semantic names for pulse. Drives the dump-generated `ila_capture.tcl`
# inside the batch Vivado FHS environment (same pattern as vivado-flow.nu).
#
# Usage:
#   nu nzea_fpga/scripts/ila-capture.nu [--mode reset|snapshot] [--port <uart>]

const board = "lxb_artix7"
const isa = "riscv32im"
const batch_nix = (path self . | path join "vivado-batch.nix")

def main [
    --mode: string = "reset"      # reset (default: arm on cpu_reset release, then uart-reset) | snapshot
    --port: string = "/dev/serial/by-id/usb-1a86_USB_Serial-if00-port0"
] {
    let vivado_dir = $"build/fpga/($board)/($isa)/hw/vivado" | path expand

    if not ($vivado_dir | path exists) {
        print $"ERROR: Vivado work root not found at ($vivado_dir)"
        print "Run: just dump --target fpga --fpgaBoard lxb_artix7 --isa riscv32im --sim false --ila"
        exit 1
    }
    let tcl = $vivado_dir | path join "ila_capture.tcl"
    if not ($tcl | path exists) {
        print "ERROR: ila_capture.tcl missing — this build has no ILA enabled"
        print "Re-dump with --ila, rebuild with just vivado-build, then program."
        exit 1
    }

    # The capture script sends the uart reset itself via the probe binary
    # (`exec probe reset <port>` inside tcl), so make sure it is built.
    cargo build --release --quiet --manifest-path probe/Cargo.toml -p probe_cli
    let probe_bin = "probe/target/release/probe" | path expand

    print $"Capturing ILA in ($mode) mode…"
    let script = $"cd ($vivado_dir)
vivado -mode batch -source ila_capture.tcl -nolog -nojournal -tclargs ($mode) ($port) ($probe_bin)
exit
"
    $script | ^nix-shell $batch_nix

    let vcd = $vivado_dir | path join "capture.vcd"
    if not ($vcd | path exists) {
        print "ERROR: no capture.vcd produced by the ILA capture"
        exit 1
    }
    let named = $vivado_dir | path join "capture.named.vcd"
    let map = $vivado_dir | path join "ila_probes.map"
    ^$probe_bin vcd-rename $map $vcd $named
    print $"ILA capture ready for pulse: ($named)"
}