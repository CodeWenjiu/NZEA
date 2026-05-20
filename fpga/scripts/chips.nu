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
        board:  "nexysVideo",
        cst:    "fpga/lxb_artix7/A7_lite.xdc",
        vivado_part: "xc7a200tsbg484-1",
        vivado:  "/mnt/d/Xilinx/Vivado/2023.1/bin/vivado.bat",
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
    let synth_family  = if $v == "gowin" { "gw2a" }        else { "xc7" }
    let pnr           = if $v == "gowin" { "nextpnr-himbaechel" } else { "nextpnr-xilinx" }
    let pack          = if $v == "gowin" { "gowin_pack" }   else { "noop" }
    let bit_ext       = if $v == "gowin" { "fs" }           else { "bit" }
    let pre_alu = if $v == "gowin" { true } else { false }
    let pnr_opts      = { placer: "heap", router: "router1" }
    let has_cst = ($raw | columns) | any {|c| $c == "cst" }
    let cst = if $has_cst {
        $raw.cst
    } else if $v == "gowin" {
        $"($board_dir)/($board).cst"
    } else {
        $"($board_dir)/($board).xdc"
    }

    let cols = ($raw | columns)
    let has_viv = $cols | any {|c| $c == "vivado" }
    let has_part = $cols | any {|c| $c == "vivado_part" }
    let vivado = if $has_viv { $raw.vivado } else { "vivado" }
    let part   = if $has_part { $raw.vivado_part } else { "" }

    # chipdb for xilinx: OPENXC7_CHIPDB/<device>.bin
    let chipdb = if $v == "xilinx" {
        let pkg = ($dev | str replace -r '-[^-]+$' '' | str replace '-' '')
        $"($env.OPENXC7_CHIPDB)/($pkg).bin"
    } else {
        ""
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
        vivado: $vivado,
        part: $part,
        chipdb: $chipdb,
        tile_platform: $raw.tile_platform,
        tile_isa:      $raw.tile_isa,
    }
}

export def chip_info [dev: string] { compute_chip $dev }