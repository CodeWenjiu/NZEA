#!/usr/bin/env nu

# Generate a Vivado project for a Xilinx board using edalize.
# All board-specific parameters (part, top module, XDC, IP snippets) are read
# from chips.nu. This script is the shared entry point: FpgaElaborate invokes it
# automatically as part of `just dump`; it can also be run manually to regenerate
# the Vivado project without re-dumping the RTL.
#
# Usage:
#   nu nzea_fpga/scripts/vivado-project.nu --board lxb_artix7
#   nu nzea_fpga/scripts/vivado-project.nu --dev xc7a200t-sbg484-1

const chips_script = (path self . | path join "chips.nu")
source $chips_script

def main [
    --dev: string        # device key from chips.nu (mutually exclusive with --board)
    --board: string      # board segment from chips.nu (mutually exclusive with --dev)
    --isa: string = "riscv32im"
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

    # DDR3 (lxb_artix7): MIG 7-series IP + DDR pin constraints. The reference
    # mig_b.prj (MIG config) and mig_ddr3.xdc (pin/LOC template from the same
    # fbg484 board) live in <board>/ddr3_ref. We rename the constraint ports to
    # match our top-level DDR3_* pins; the IP itself is created from mig_b.prj
    # by vivado-project.py (--mig-prj). Enabled when the reference files exist.
    let ddr3_ref = $"($ip_dir)/ddr3_ref"
    let mig_prj = $"($ddr3_ref)/srcs/sources_1/ip/mig_ddr3/mig_b.prj"
    let mig_xdc_tpl = $"($ddr3_ref)/mig_ddr3.xdc"

    mut xdcs = [$xdc]
    mut mig_args = []
    if (($mig_prj | path exists) and ($mig_xdc_tpl | path exists)) {
        print "DDR3 reference found — enabling MIG IP + DDR pin constraints"
        let ddr_pins_xdc = $"($rtl_dir)/ddr3_pins.xdc"
        # Port rename: MIG example template uses ddr3_* nets; our top uses DDR3_*.
        # 1-bit vectors in the template ([0]) collapse to scalar ports in our
        # firtool output, so drop the index for those (ck_p/ck_n/cke/odt).
        let ddr_xdc_content = (
            open --raw $mig_xdc_tpl
            | str replace -a "ddr3_" "DDR3_"
            | str replace -a "{DDR3_ck_p[0]}" "{DDR3_ck_p}"
            | str replace -a "{DDR3_ck_n[0]}" "{DDR3_ck_n}"
            | str replace -a "{DDR3_cke[0]}" "{DDR3_cke}"
            | str replace -a "{DDR3_odt[0]}" "{DDR3_odt}"
        )
        $ddr_xdc_content | save --force $ddr_pins_xdc
        $xdcs = ($xdcs | append ($ddr_pins_xdc | path expand))
        $mig_args = ["--mig-prj" ($mig_prj | path expand)]
    }

    # The ILA is enabled by instantiating the IlaProbes wrapper in the board top
    # (debugging only). Because that wrapper is a plain Module, firtool emits it as
    # a standalone IlaProbes.sv file exactly when it is instantiated and drops it
    # otherwise; FpgaElaborate then writes an ila_config.json carrying the probe
    # widths. The *presence of that file* is the single source of truth: creating
    # the ILA IP follows the RTL instantiation with no separate switch to keep in
    # sync. vivado-project.py reads ila_config.json and generates the ILA IP
    # snippet with the exact probe widths.
    let rtl_has_ila = ($"($rtl_dir)/ila_config.json" | path exists)
    if $rtl_has_ila {
        print "RTL instantiates IlaProbes — enabling ILA IP"
    }

    # Collect existing IP snippets as expanded absolute paths.
    let ip_tcls = (
        if ($ip_clk_wiz | path exists) { [ ($ip_clk_wiz | path expand) ] } else { [] }
    )

    let vivado_dir = $"($rtl_dir)/vivado" | path expand

    print $"Generating Vivado project via edalize at: ($vivado_dir)"
    let args = ([ "--rtl-dir" $rtl_dir "--part" $part "--top" $top "--out" $vivado_dir ]
        | append ($xdcs | each {|x| ["--xdc" $x] } | flatten)
        | append (($ip_tcls | each {|t| ["--ip-tcl" $t] } | flatten))
        | append $mig_args)

    cd nzea_fpga/tools/vivado
    uv run vivado-project.py ...$args
}
