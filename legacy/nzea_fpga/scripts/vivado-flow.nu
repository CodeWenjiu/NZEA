#!/usr/bin/env nu

# Run the Vivado build and/or programming flow headlessly.
#
# Drives the generated `build_all.tcl` (synthesis + implementation + bitstream)
# and/or `program.tcl` (JTAG programming) inside the batch Vivado FHS
# environment (vivado-batch.nix). Run from the repo root.
#
# Usage:
#   nu nzea_fpga/scripts/vivado-flow.nu --board lxb_artix7 [--build-only | --program-only]

const chips_script = (path self . | path join "chips.nu")
const batch_nix = (path self . | path join "vivado-batch.nix")
source $chips_script

# Drive one Vivado batch invocation inside the FHS env.
# nix-shell's runScript is an interactive bash; --run/--command are unreliable,
# so the Tcl commands are piped into its stdin instead.
def run_vivado_tcl [vivado_dir: string, tcl: string] {
    let tcl_path = $"($vivado_dir)/($tcl)"
    if not ($tcl_path | path exists) {
        print $"ERROR: ($tcl) not found in ($vivado_dir)"
        exit 1
    }
    let msg = ["Running Vivado: " $tcl " in " $vivado_dir] | str join
    print $msg
    let script = $"cd ($vivado_dir)
vivado -mode batch -source ($tcl) -nolog -nojournal
exit
"
    $script | ^nix-shell $batch_nix
}

def main [
    --board: string = "lxb_artix7"
    --isa: string = "riscv32im"
    --build-only        # only run build_all.tcl (skip programming)
    --program-only      # only run program.tcl (skip build)
] {
    let chip = (chip_by_board $board)
    if $chip.synth != "synth_xilinx" {
        print $"Error: board ($board) is not a Xilinx device"
        exit 1
    }

    let vivado_dir = $"build/fpga/($chip.board)/($isa)/hw/vivado" | path expand
    if not ($vivado_dir | path exists) {
        print $"Vivado project not found at ($vivado_dir)"
        print "Run: just dump --target fpga --fpgaBoard <board> --isa <isa> --sim false"
        exit 1
    }
    if not ($batch_nix | path exists) {
        print $"Batch FHS env not found at ($batch_nix)"
        exit 1
    }

    if (not $program_only) {
        run_vivado_tcl $vivado_dir "build_all.tcl"
    }
    if (not $build_only) {
        run_vivado_tcl $vivado_dir "program.tcl"
    }

    print "Vivado flow finished."
}
