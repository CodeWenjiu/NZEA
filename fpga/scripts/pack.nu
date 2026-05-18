#!/usr/bin/env nu
# FPGA build: RTL → JSON → routed JSON → .fs bitstream
# Each step skips if its output already exists.

def synth [rtl: string, build: string, top: string] {
    let json = $"($build)/($top).json"
    if ($json | path exists) { return }
    print "Synthesizing..."
    mkdir $build
    yosys -l $"($build)/($top)_synth.log" -p $"read_verilog ($rtl); synth_gowin -json ($json) -family gw2a -top ($top)"
}

def pnr [build: string, top: string, dev: string, fam: string, cst: string] {
    let pnr_json = $"($build)/($top)_pnr.json"
    if ($pnr_json | path exists) { return }
    print "Placing & routing..."
    nextpnr-himbaechel --json $"($build)/($top).json" --write $pnr_json --device $dev --vopt $"family=($fam)" --vopt $"cst=($cst)" --placer heap --router router1 --timing-allow-fail
}

def pack [build: string, top: string, fam: string] {
    let fs = $"($build)/($top).fs"
    if ($fs | path exists) { return }
    print "Packing bitstream..."
    gowin_pack -c -d $fam -o $fs $"($build)/($top)_pnr.json"
}

def main [rtl: string, build: string, top: string, dev: string, fam: string, cst: string] {
    synth $rtl $build $top
    pnr $build $top $dev $fam $cst
    pack $build $top $fam
}
