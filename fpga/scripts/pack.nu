#!/usr/bin/env nu
# FPGA build: RTL → JSON → routed JSON → bitstream

source ./chips.nu

def tile_rtl_path [chip: record] {
    $"build/tile/($chip.tile_platform)/($chip.tile_isa)/hw"
}

# Regenerate tile RTL if Scala sources are newer
def dump_tile [chip: record] {
    let trtl = tile_rtl_path $chip
    let filelist = $"($trtl)/filelist.f"
    mut need_gen = false

    if not ($filelist | path exists) {
        $need_gen = true
    } else {
        let rtl_mtime = (ls $filelist | first | get modified)
        let scala_srcs = (glob nzea_*/src/**/*.scala)
        let scala_latest = $scala_srcs | each {|f| ls $f | first | get modified} | sort | last
        if ($scala_latest > $rtl_mtime) {
            print "Tile RTL out of date, regenerating..."
            $need_gen = true
        }
    }

    if $need_gen {
        ^just dump --target tile --platform $chip.tile_platform --isa $chip.tile_isa --sim false --robDepth 8 --issueQueueDepth 2
    }
}

# Per-platform RTL pre-processing
def preprocess [chip: record, tile_rtl: string] {
    if $chip.pre_alu and ($tile_rtl | path exists) {
        print $"Pre-processing: renaming ALU → ALU_nzea in ($tile_rtl)/*.sv"
        for f in (glob $"($tile_rtl)/*.sv") {
            let content = open $f
            if ($content | str contains "module ALU") or ($content | str contains "ALU alu") {
                $content
                    | str replace -a "module ALU" "module ALU_nzea"
                    | str replace -a "ALU alu" "ALU_nzea alu"
                    | save -f $f
            }
        }
    }
}

def synth [rtl: string, build: string, top: string, chip: record, tile_rtl: string] {
    let json = $"($build)/($top).json"
    let rtl_mtime = (ls $rtl | first | get modified)
    let cst_mtime = (ls $chip.cst | first | get modified)
    let src_latest = if $rtl_mtime > $cst_mtime { $rtl_mtime } else { $cst_mtime }

    if ($json | path exists) {
        let json_mtime = (ls $json | first | get modified)
        if $json_mtime > $src_latest { return }
    }
    print "Synthesizing..."
    mkdir $build

    let tile_sv = glob $"($tile_rtl)/*.sv"
    let rtl_files = if ($tile_sv | length) > 0 {
        $"($rtl) ($tile_rtl)/*.sv"
    } else {
        $rtl
    }

    let edif = $"($build)/($top).edif"
    mut synth_args = $"($chip.synth) -json ($json) -family ($chip.synth_family)"
    if $chip.synth == "synth_xilinx" {
        $synth_args = $"($synth_args) -edif ($edif)"
    }

    yosys -l $"($build)/($top)_synth.log" -p $"read_verilog ($rtl_files); hierarchy -top ($top); ($synth_args); tee -o ($build)/($top)_stat.json stat -json" o+e> /dev/null
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
    let top = "top"
    let build = "build/fpga"
    let rtl = $"($chip.board_dir)/($top).v"
    let trtl = tile_rtl_path $chip

    # Ensure tile RTL is up-to-date
    if ($rtl | path exists) and (open $rtl | str contains "NzeaTile") {
        dump_tile $chip
    }

    preprocess $chip $trtl
    synth $rtl $build $top $chip $trtl
    pnr $build $top $dev $chip
}