#!/usr/bin/env nu
# Flash FPGA bitstream.

source ./chips.nu

def main [--dev: string = "GW2AR-LV18QN88C8/I7", --flash] {
    let chip = chip_info $dev
    let top = $chip.top_module
    let build = "build/fpga"
    let bit = $"($build)/($top).($chip.bit_ext)"

    if not ($bit | path exists) {
        error make -u { msg: $"Bitstream not found: ($bit). Run 'just pack' first." }
    }

    let mode = if $flash { "-f" } else { "" }
    print $"Programming ($dev) as ($chip.prog_board) with ($bit)..."
    if $mode != "" {
        ^openFPGALoader -b $chip.prog_board $mode --verify $bit
    } else {
        ^openFPGALoader -b $chip.prog_board --verify $bit
    }
}
