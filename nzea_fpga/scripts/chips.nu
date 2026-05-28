# FPGA chip database.
# Each entry maps a device ID to board metadata used by pack / prog / report.

const raw_chips = {
    "GW2AR-LV18QN88C8/I7": {
        vendor: "gowin",
        board:  "tangnano20k",
        top_module: "TangNano20kTop",
    },
    "xc7a200t-sbg484-1": {
        vendor: "xilinx",
        board:  "lxb_artix7",
        top_module: "LxbArtix7Top",
        cst:    "nzea_fpga/src/boards/lxb_artix7/A7_lite.xdc",
        vivado_part: "xc7a200tsbg484-1",
    },
}

def compute_chip [dev: string] {
    let raw = $raw_chips | get -o $dev
    if ($raw | is-empty) {
        error make -u { msg: $"Unknown device '($dev)'. Add it to nzea_fpga/scripts/chips.nu" }
    }
    let v = $raw.vendor
    let board = $raw.board
    let board_dir = $"nzea_fpga/($board)"

    let synth        = if $v == "gowin" { "synth_gowin" } else { "synth_xilinx" }
    let synth_family = if $v == "gowin" { "gw2a" }        else { "xc7" }
    let pnr          = if $v == "gowin" { "nextpnr-himbaechel" } else { "nextpnr-xilinx" }
    let bit_ext      = if $v == "gowin" { "fs" }           else { "bit" }
    let pnr_opts     = { placer: "heap", router: "router1" }

    let has_cst = ($raw | columns) | any {|c| $c == "cst" }
    # Gowin: CST is under src/boards/<board>/
    let cst = if $has_cst {
        $raw.cst
    } else if $v == "gowin" {
        $"nzea_fpga/src/boards/($board)/($board).cst"
    } else {
        $"nzea_fpga/src/boards/($board)/($board).xdc"
    }

    let cols = ($raw | columns)
    let has_viv = $cols | any {|c| $c == "vivado" }
    let has_part = $cols | any {|c| $c == "vivado_part" }
    let vivado = if $has_viv { $raw.vivado } else { "vivado" }
    let part   = if $has_part { $raw.vivado_part } else { "" }

    let has_prog = $cols | any {|c| $c == "prog_board" }
    let prog_board = if $has_prog { $raw.prog_board } else { $board }

    {
        synth: $synth,
        synth_family: $synth_family,
        pnr: $pnr,
        pnr_opts: $pnr_opts,
        bit_ext: $bit_ext,
        board: $board,
        prog_board: $prog_board,
        board_dir: $board_dir,
        cst: $cst,
        vivado: $vivado,
        part: $part,
        top_module: $raw.top_module,
    }
}

export def chip_info [dev: string] { compute_chip $dev }
