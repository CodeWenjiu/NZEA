#!/usr/bin/env nu
# Compile testbench + RTL with iverilog.
# Usage: nu iverilog_tb/scripts/iv-build.nu <platform> <isa>

def main [platform: string, isa: string] {
    let rtl = $"build/tile/($platform)/($isa)/hw"
    let rtl_glob = $"($rtl)/*.sv"
    mut rtl_sv = (glob $rtl_glob)
    let tb_sv  = (glob iverilog_tb/*.sv)

    if ($rtl_sv | length) == 0 or (not ($rtl | path join "filelist.f" | path exists)) {
        print "RTL not found, generating..."
        ^just dump --target tile --platform $platform --isa $isa --sim false
        $rtl_sv = (glob $rtl_glob)
    }

    let iv_dir = $rtl | path join "iverilog"
    mkdir $iv_dir

    for f in (glob iverilog_tb/*.hex) {
        cp $f $iv_dir
    }

    print "Compiling with iverilog..."
    ^iverilog -g2012 -Wall -Wno-timescale -o ($iv_dir | path join "tb.vvp") ...$tb_sv ...$rtl_sv
}
