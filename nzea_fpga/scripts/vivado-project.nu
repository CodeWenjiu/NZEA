#!/usr/bin/env nu

# Generate a Vivado project for a Xilinx board using edalize.
# All board-specific parameters (part, top module, XDC, IP snippets) are read
# from chips.nu, so this script is the single entry point shared by both the
# manual `just vivado-project` flow and the automatic `just dump` flow.
#
# Usage:
#   nu nzea_fpga/scripts/vivado-project.nu --board lxb_artix7 [--enable-ila]
#   nu nzea_fpga/scripts/vivado-project.nu --dev xc7a200t-sbg484-1 [--enable-ila]

const chips_script = (path self . | path join "chips.nu")
source $chips_script

def main [
    --dev: string        # device key from chips.nu (mutually exclusive with --board)
    --board: string      # board segment from chips.nu (mutually exclusive with --dev)
    --isa: string = "riscv32im"
    --enable-ila         # also create the ILA IP (u_ila_0)
] {
    let use_board = ($board | is-not-empty)
    let chip = (
        if $use_board { chip_by_board $board }
        else if ($dev | is-not-empty) { chip_info $dev }
        else { error make -u { msg: "Must specify --board or --dev" } }
    )
    if $chip.synth != "synth_xilinx" {
        print $"Error: board ($chip.board) is not a Xilinx device"
        exit 1
    }

    let rtl_dir = $"build/fpga/($chip.board)/($isa)/hw" | path expand
    if not ($rtl_dir | path exists) {
        print $"FPGA RTL not found at ($rtl_dir)"
        print "Run: just dump --target fpga --fpgaBoard <board> --isa <isa> --sim false"
        exit 1
    }

    let xdc  = $chip.cst | path expand
    let part = $chip.part
    let top  = $chip.top_module

    # Board-specific IP creation snippets (Xilinx clk_wiz/ILA); sourced by edalize.
    let ip_dir = $"nzea_fpga/src/boards/($chip.board)"
    let ip_clk_wiz = $"($ip_dir)/ip_clk_wiz.tcl"
    let ip_ila = $"($ip_dir)/ip_ila.tcl"

    # Collect existing IP snippets as expanded absolute paths.
    let ip_tcls = (
        if ($ip_clk_wiz | path exists) { [ ($ip_clk_wiz | path expand) ] } else { [] }
    ) | append (
        if ($enable_ila) and ($ip_ila | path exists) { [ ($ip_ila | path expand) ] } else { [] }
    )

    let vivado_dir = $"($rtl_dir)/vivado" | path expand

    print $"Generating Vivado project via edalize at: ($vivado_dir)"
    let args = (["--rtl-dir" $rtl_dir "--part" $part "--xdc" $xdc "--top" $top "--out" $vivado_dir]
        | append (($ip_tcls | each {|t| ["--ip-tcl" $t] } | flatten)))

    cd nzea_fpga/tools/vivado
    uv run vivado-project.py ...$args
}
