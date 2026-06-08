#!/usr/bin/env nu
# Compile testbench + RTL with iverilog.
# Usage: nu nzea_tile/sim/scripts/iv-build.nu <platform> <isa> [hex]

def main [platform: string, isa: string, hex?: string] {
    let rtl = $"build/tile/($platform)/($isa)/hw"
    let rtl_glob = $"($rtl)/*.sv"
    let filelist = $rtl | path join "filelist.f"

    mut need_gen = false

    if ((glob $rtl_glob | length) == 0 or (not ($filelist | path exists))) {
        $need_gen = true
    } else {
        # Check if Scala sources are newer than generated RTL
        let rtl_mtime = (ls $filelist | first | get modified)
        let scala_srcs = (glob nzea_*/src/**/*.scala)
        let scala_latest = (
            $scala_srcs
            | each {|f| ls $f | first | get modified}
            | sort
            | last
        )
        if ($scala_latest > $rtl_mtime) {
            $need_gen = true
            print "RTL out of date, regenerating..."
        }
    }

    if $need_gen {
        ^just dump --target tile --platform $platform --isa $isa --sim false
    }

    mut rtl_sv = (glob $rtl_glob)

    let iv_dir = $rtl | path join "iverilog"
    mkdir $iv_dir

    # Copy and size the hex file (supplied as argument or defaulted by justfile)
    mut hex_buf = 256
    if ($hex | is-not-empty) and ($hex | path exists) {
        cp $hex $iv_dir
        let hsize = try { open $hex | lines | length } catch { 0 }
        if $hsize > 0 { $hex_buf = $hsize }
    }

    print "Compiling with iverilog..."
    ^iverilog -g2012 -Wall -Wno-timescale -DHEX_BUF_WORDS=($hex_buf) -o ($iv_dir | path join "tb.vvp") ...(glob nzea_tile/sim/*.sv) ...$rtl_sv
}
