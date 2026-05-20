#!/usr/bin/env nu
# Compile testbench + RTL with iverilog.
# Usage: nu iverilog_tb/scripts/iv-build.nu <platform> <isa>

def main [platform: string, isa: string] {
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

    for f in (glob iverilog_tb/*.hex) {
        cp $f $iv_dir
    }

    # Determine max hex words for $readmemh array sizing
    let hex_files = glob iverilog_tb/*.hex
    let hex_max = if ($hex_files | length) > 0 {
        $hex_files | each {|f| try { open $f | lines | length } catch { 0 }} | math max
    } else {
        0
    }
    let hex_buf = if $hex_max > 0 { $hex_max } else { 256 }

    print "Compiling with iverilog..."
    ^iverilog -g2012 -Wall -Wno-timescale -DHEX_BUF_WORDS=($hex_buf) -o ($iv_dir | path join "tb.vvp") ...(glob iverilog_tb/*.sv) ...$rtl_sv
}