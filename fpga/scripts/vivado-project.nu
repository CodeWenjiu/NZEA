#!/usr/bin/env nu

# Generate a Vivado project under build/tile/<platform>/<isa>/hw/vivado/
# Usage: nu fpga/scripts/vivado-project.nu --dev xc7a200t-sbg484-1

const chips_script = (path self . | path join "chips.nu")
source $chips_script

def main [
    --dev: string       # device key from chips.nu
    --clock-hz: int = 100_000_000
] {
    let chip = (chip_info $dev)
    if $chip.synth != "synth_xilinx" {
        print $"Error: ($dev) is not a Xilinx device"
        exit 1
    }

    let tile_dir = $"build/tile/($chip.tile_platform)/($chip.tile_isa)/hw"
    if not ($tile_dir | path exists) {
        print $"Tile RTL not found at ($tile_dir)"
        print "Run: just dump-tile <platform> <isa> <clock_hz>"
        exit 1
    }

    let vivado_dir = $"($tile_dir)/vivado"
    mkdir $vivado_dir

    let top  = $"($chip.board_dir)/top.v" | path expand
    let xdc  = $chip.cst | path expand

    let tile_dir_norm = ($tile_dir | path expand)
    let part = $chip.part

    # list all .sv files in tile dir (for explicit add_files)
    let sv_files = (glob $"($tile_dir)/*.sv")
    let add_sv_lines = $sv_files | each {|f| $"add_files -norecurse ($f)" }

    let tcl = $"($vivado_dir)/create_project.tcl"
    [
        $"create_project -force nzea_tile ./nzea_tile -part ($part)"
        $"add_files -norecurse ($top)"
    ] | append $add_sv_lines | append [
        $"add_files -fileset constrs_1 -norecurse ($xdc)"
        "set_property top top [current_fileset]"
        $"set_property include_dirs [file normalize ($tile_dir)] [current_fileset]"
        "update_compile_order -fileset sources_1"
        "update_compile_order -fileset sim_1"
    ] | str join (char newline) | save -f $tcl

    print $"Vivado project: ($vivado_dir)"
    print $"Source in Vivado Tcl Console:"
    print $"  source ($tcl)"
}
