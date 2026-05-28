#!/usr/bin/env nu
# FPGA build: RTL → JSON → routed JSON → bitstream.
#
# Expects Chisel-generated RTL under build/fpga/<board>/<isa>/hw/.
# Run `just dump-fpga <board> <isa>` first, or let this script do it lazily.

source ./chips.nu

# Regenerate FPGA RTL if Scala sources are newer than filelist
def dump_fpga [board: string, isa: string, clock_hz: int] {
    let rtl_dir = $"build/fpga/($board)/($isa)/hw"
    let filelist = $"($rtl_dir)/filelist.f"
    mut need_gen = false

    if not ($filelist | path exists) {
        $need_gen = true
    } else {
        let rtl_mtime = (ls $filelist | first | get modified)
        let scala_srcs = (glob nzea_*/src/**/*.scala)
        let scala_latest = $scala_srcs | each {|f| ls $f | first | get modified} | sort | last
        if ($scala_latest > $rtl_mtime) {
            print "FPGA RTL out of date, regenerating..."
            $need_gen = true
        }
    }

    if $need_gen {
        ^just dump --target fpga --fpgaBoard $board --isa $isa --sim false --clockHz $"($clock_hz)"
    }
}

# Gowin: rename ALU module to avoid conflict with built-in primitive.
# Uses exact patterns to avoid double-processing on re-runs.
def preprocess_alu [rtl_dir: string] {
    if ($rtl_dir | path exists) {
        print $"Pre-processing: renaming ALU → ALU_nzea in ($rtl_dir)/*.sv"
        for f in (glob $"($rtl_dir)/*.sv") {
            let content = open $f
            let has_mod = ($content | str contains "module ALU(") or ($content | str contains "module ALU ")
            let has_inst = ($content | str contains "ALU alu (")
            if $has_mod or $has_inst {
                mut c = $content
                if $has_mod {
                    $c = ($c | str replace -a "module ALU(" "module ALU_nzea(")
                    $c = ($c | str replace -a "module ALU " "module ALU_nzea ")
                }
                if $has_inst {
                    $c = ($c | str replace -a "ALU alu (" "ALU_nzea alu (")
                }
                $c | save -f $f
            }
        }
    }
}

def synth [rtl_dir: string, build: string, top: string, chip: record] {
    let json = $"($build)/($top).json"
    let sv_files = (glob $"($rtl_dir)/*.sv")
    let rtl_mtime = ($sv_files | each {|f| (ls $f | first | get modified)} | sort | last)
    let cst_mtime = (ls $chip.cst | first | get modified)
    let src_latest = if $rtl_mtime > $cst_mtime { $rtl_mtime } else { $cst_mtime }

    if ($json | path exists) {
        let json_mtime = (ls $json | first | get modified)
        if $json_mtime > $src_latest { return }
    }
    print "Synthesizing..."
    mkdir $build

    let edif = $"($build)/($top).edif"
    mut synth_args = $"($chip.synth) -json ($json) -family ($chip.synth_family)"
    if $chip.synth == "synth_xilinx" {
        $synth_args = $"($synth_args) -edif ($edif)"
    }

    yosys -l $"($build)/($top)_synth.log" -p $"read_verilog ($rtl_dir)/*.sv; hierarchy -top ($top); ($synth_args); tee -o ($build)/($top)_stat.json stat -json" o+e> /dev/null
}

def pnr [build: string, top: string, dev: string, chip: record] {
    let json = $"($build)/($top).json"
    let pnr_log  = $"($build)/($top)_pnr.log"
    let bit = $"($build)/($top).($chip.bit_ext)"

    # Xilinx: synth only (use Vivado for PnR / prog)
    if $chip.synth == "synth_xilinx" {
        print "Xilinx synth done. Use Vivado for PnR and programming."
        return
    }

    # Gowin PnR
    let pnr_out = $"($build)/($top)_pnr.json"
    let json_mtime = (ls $json | first | get modified)
    let cst_mtime  = (ls $chip.cst | first | get modified)
    let src_latest = if $json_mtime > $cst_mtime { $json_mtime } else { $cst_mtime }

    if ($pnr_out | path exists) {
        let pnr_mtime = (ls $pnr_out | first | get modified)
        if $pnr_mtime > $src_latest { return }
    }
    print "Placing & routing..."

    let popts = $chip.pnr_opts
    mut pnr_args = [
        --json $json,
        --write $pnr_out,
        --device $dev,
        --placer $popts.placer,
        --router $popts.router,
        --timing-allow-fail,
        --report $"($build)/($top)_report.json",
        --vopt $"family=GW2A-18C",
        --vopt $"cst=($chip.cst)",
    ]

    ^$chip.pnr ...$pnr_args o+e> $pnr_log

    print "Packing bitstream..."
    let pnr_json = $"($build)/($top)_pnr.json"
    if ($bit | path exists) {
        let bit_mtime = (ls $bit | first | get modified)
        let pnr_mtime = (ls $pnr_json | first | get modified)
        if $bit_mtime > $pnr_mtime { return }
    }
    ^gowin_pack -c -d GW2A-18C -o $bit $pnr_json
}

def main [--dev: string = "GW2AR-LV18QN88C8/I7"] {
    let chip = chip_info $dev
    let top = $chip.top_module
    let board = $chip.board
    let isa = "riscv32i"  # default; override via --isa flag if needed
    let build = "build/fpga"

    let rtl_dir = $"build/fpga/($board)/($isa)/hw"

    # Ensure FPGA RTL is up-to-date
    dump_fpga $board $isa 100_000_000

    if $chip.pre_alu {
        preprocess_alu $rtl_dir
    }

    synth $rtl_dir $build $top $chip
    pnr $build $top $dev $chip
}
