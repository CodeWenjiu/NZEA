# FPGA chip database.
# Each entry only specifies vendor + board; everything else is derived.

const raw_chips = {
    "GW2AR-LV18QN88C8/I7": {
        vendor: "gowin",
        board:  "tangnano20k",
        tile_platform: "hellofpga",
        tile_isa:  "riscv32i",
    },
    "xc7a200t-sbg484-1": {
        vendor: "xilinx",
        board:  "arty_a7_200t",
        tile_platform: "hellofpga",
        tile_isa:  "riscv32i",
    },
}

def compute_chip [dev: string] {
    let raw = $raw_chips | get -o $dev
    if ($raw | is-empty) {
        error make -u { msg: $"Unknown device '($dev)'. Add it to fpga/scripts/chips.nu" }
    }
    let v = $raw.vendor
    let board = $raw.board
    let board_dir = $"fpga/($board)"

    let synth         = if $v == "gowin" { "synth_gowin" } else { "synth_xilinx" }
    let synth_family  = if $v == "gowin" { "gw2a" }        else { "artix7" }
    let pnr           = if $v == "gowin" { "nextpnr-himbaechel" } else { "nextpnr-xilinx" }
    let pack          = if $v == "gowin" { "gowin_pack" }   else { "noop" }
    let bit_ext       = if $v == "gowin" { "fs" }           else { "bit" }
    let pre_alu = if $v == "gowin" { true } else { false }
    let pnr_opts      = { placer: "heap", router: "router1" }
    let cst           = if $v == "gowin" {
        $"($board_dir)/($board).cst"
    } else {
        $"($board_dir)/($board).xdc"
    }

    {
        synth: $synth,
        synth_family: $synth_family,
        pnr: $pnr,
        pnr_opts: $pnr_opts,
        pack: $pack,
        bit_ext: $bit_ext,
        pre_alu: $pre_alu,
        board: $board,
        board_dir: $board_dir,
        cst: $cst,
        tile_platform: $raw.tile_platform,
        tile_isa:      $raw.tile_isa,
    }
}

export def chip_info [dev: string] { compute_chip $dev }