# FPGA board database: what the Vivado flow needs, one entry per board, and where a board's build tree is.
#
# Board facts live here rather than in the RTL: the flow is a shell script and cannot ask the elaborator, and the pin
# map is the board's, not the design's. The RTL side names the same board by its segment (`--board`, and the platform
# segment of `build/fpga/<board>/<isa>/<flow>`), which is the table's key.
const raw_boards = {
    "lxb_artix7": {
        top_module: "Top",
        part: "xc7a200tfbg484-2",
        xdc: "nzea_fpga/src/boards/lxb_artix7/pins.xdc",
    },
}

# Look a board up by its segment (e.g. "lxb_artix7"), expanding its constraint paths.
export def board_info [board: string] {
    let b = $raw_boards | get -o $board
    if ($b | is-empty) {
        error make -u { msg: $"Unknown board '($board)'. Add it to nzea_fpga/scripts/chips.nu" }
    }
    {
        board: $board,
        top_module: $b.top_module,
        part: $b.part,
        xdc: ($b.xdc | path expand),
    }
}

# Where a board's dumped RTL lives: the one layout rule (`build/<target>/<platform>/<isa>`), a board build's platform
# being the board. The scripts cannot ask the elaborator, so the rule is written here — beside the board facts they all
# source — once.
export def fpga_rtl_dir [board: string, isa: string] {
    $"build/fpga/($board)/($isa)" | path expand
}
