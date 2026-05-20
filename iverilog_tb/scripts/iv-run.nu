#!/usr/bin/env nu
# Run compiled iverilog simulation.
# Usage: nu iverilog_tb/scripts/iv-run.nu <platform> <isa> [hex] [boot] [wave]

def main [
    platform: string
    isa: string
    hex: string = "hello.hex"
    boot: string = "dir"
    wave: string = "0"
] {
    let iv_dir = $"build/tile/($platform)/($isa)/hw/iverilog"
    cd $iv_dir

    mut wflag = ""
    if $wave == "1" { $wflag = "+WAVE=1" }

    let hsize = (try { open $hex | lines | length } catch { 0 })
    mut hflag = ""
    if $hsize > 0 { $hflag = $"+HEX_SIZE=($hsize)" }

    ^vvp tb.vvp $"+HEX=($hex)" $"+BOOT=($boot)" $wflag $hflag

}
