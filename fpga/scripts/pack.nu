#!/usr/bin/env nu
# FPGA build: RTL → JSON → routed JSON → .fs bitstream
# Each step re-runs when its sources are newer than its output.

def synth [rtl: string, build: string, top: string, cst: string] {
    let json = $"($build)/($top).json"
    let rtl_mtime = (ls $rtl | first | get modified)
    let cst_mtime = (ls $cst | first | get modified)
    let src_latest = if $rtl_mtime > $cst_mtime { $rtl_mtime } else { $cst_mtime }

    if ($json | path exists) {
        let json_mtime = (ls $json | first | get modified)
        if $json_mtime > $src_latest { return }
    }
    print "Synthesizing..."
    mkdir $build
    yosys -l $"($build)/($top)_synth.log" -p $"read_verilog ($rtl); synth_gowin -json ($json) -family gw2a -top ($top); tee -o ($build)/($top)_stat.json stat -json" o+e> /dev/null
}

def pnr [build: string, top: string, dev: string, fam: string, cst: string] {
    let json = $"($build)/($top).json"
    let pnr_json = $"($build)/($top)_pnr.json"
    let pnr_log  = $"($build)/($top)_pnr.log"
    let json_mtime = (ls $json | first | get modified)
    let cst_mtime  = (ls $cst | first | get modified)
    let src_latest = if $json_mtime > $cst_mtime { $json_mtime } else { $cst_mtime }

    if ($pnr_json | path exists) {
        let pnr_mtime = (ls $pnr_json | first | get modified)
        if $pnr_mtime > $src_latest { return }
    }
    print "Placing & routing..."
    nextpnr-himbaechel --json $json --write $pnr_json --device $dev --vopt $"family=($fam)" --vopt $"cst=($cst)" --placer heap --router router1 --timing-allow-fail --report $"($build)/($top)_report.json" o+e> $pnr_log
}

def pack [build: string, top: string, fam: string] {
    let pnr_json = $"($build)/($top)_pnr.json"
    let fs = $"($build)/($top).fs"
    let pnr_mtime = (ls $pnr_json | first | get modified)

    if ($fs | path exists) {
        let fs_mtime = (ls $fs | first | get modified)
        if $fs_mtime > $pnr_mtime { return }
    }
    print "Packing bitstream..."
    gowin_pack -c -d $fam -o $fs $pnr_json
}

def main [rtl: string, build: string, top: string, dev: string, fam: string, cst: string] {
    synth $rtl $build $top $cst
    pnr $build $top $dev $fam $cst
    pack $build $top $fam
}