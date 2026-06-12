#!/usr/bin/env nu
# Run compiled iverilog simulation for a given target.
# Usage: nu nzea_sim/scripts/iv-run.nu <target> <platform> <isa> [hex] [boot] [wave]

def main [
    target: string
    platform: string
    isa: string
    hex: string = "hello.hex"
    boot: string = "dir"
    wave: string = "0"
] {
    let hex_name = ($hex | path basename)
    let iv_dir = $"build/sim/($target)/($platform)/($isa)/hw/iverilog"
    cd $iv_dir

    mut wflag = ""
    if $wave == "1" { $wflag = "+WAVE=1" }

    let hsize = (try { open $hex_name | lines | length } catch { 0 })
    mut hflag = ""
    if $hsize > 0 { $hflag = $"+HEX_SIZE=($hsize)" }

    ^vvp tb.vvp $"+HEX=($hex_name)" $"+BOOT=($boot)" $wflag $hflag
}
