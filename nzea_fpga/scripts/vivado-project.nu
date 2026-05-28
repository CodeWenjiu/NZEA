#!/usr/bin/env nu

# Generate a Vivado project under build/fpga/<board>/<isa>/hw/vivado/
# Usage: nu nzea_fpga/scripts/vivado-project.nu --dev xc7a200t-sbg484-1

const chips_script = (path self . | path join "chips.nu")
source $chips_script

def main [
    --dev: string       # device key from chips.nu
    --isa: string = "riscv32i"
    --clock-hz: int = 100_000_000
] {
    let chip = (chip_info $dev)
    if $chip.synth != "synth_xilinx" {
        print $"Error: ($dev) is not a Xilinx device"
        exit 1
    }

    let rtl_dir = $"build/fpga/($chip.board)/($isa)/hw"
    if not ($rtl_dir | path exists) {
        print $"FPGA RTL not found at ($rtl_dir)"
        print "Run: just dump --target fpga --fpgaBoard <board> --isa <isa> --sim false"
        exit 1
    }

    let vivado_dir = $"($rtl_dir)/vivado"
    mkdir $vivado_dir

    let xdc  = $chip.cst | path expand
    let part = $chip.part

    # list all .sv files in RTL dir (for explicit add_files)
    let sv_files = (glob $"($rtl_dir)/*.sv")
    let add_sv_lines = $sv_files | each {|f| $"add_files -norecurse ($f)" }

    let tcl = $"($vivado_dir)/create_project.tcl"
    [
        $"create_project -force nzea_fpga ./nzea_fpga -part ($part)"
    ] | append $add_sv_lines | append [
        $"add_files -fileset constrs_1 -norecurse ($xdc)"
        $"set_property top ($chip.top_module) [current_fileset]"
        $"set_property include_dirs [file normalize ($rtl_dir)] [current_fileset]"
        "update_compile_order -fileset sources_1"
        "update_compile_order -fileset sim_1"
    ] | str join (char newline) | save -f $tcl

    print $"Vivado project: ($vivado_dir)"
    print $"Source in Vivado Tcl Console:"
    print $"  source ($tcl)"
}
