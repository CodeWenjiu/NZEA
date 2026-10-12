#!/usr/bin/env nu

# Generate the Vivado project for a board: edalize writes the project tcl, the one-shot build script (build_all.tcl)
# and the programming script (program.tcl) into the board's work root.
#
# Board facts (part, top module, XDC) come from chips.nu. Everything else the project needs travels with the RTL the
# dump wrote: the sources through `filelist.f`, and the vendor-IP snippets — one tcl per IP the design instantiates —
# through `ipfiles.f` (see `nzea_fpga/src/ip/`).
#
# Run from the repo root:
#   nu nzea_fpga/scripts/vivado-project.nu --board lxb_artix7 --isa riscv32i

const chips_script = (path self . | path join "chips.nu")
source $chips_script

def main [
    --board: string = "lxb_artix7"
    --isa: string = "riscv32i"
] {
    let info = board_info $board
    let rtl_dir = fpga_rtl_dir $board $isa
    if not ($rtl_dir | path exists) {
        print $"FPGA RTL not found at ($rtl_dir)"
        print "Run: just fpga dump <board> <isa>"
        exit 1
    }

    let vivado_dir = $"($rtl_dir)/vivado"
    print $"Generating Vivado project via edalize at: ($vivado_dir)"
    cd nzea_fpga/tools/vivado
    uv run vivado-project.py --rtl-dir $rtl_dir --part $info.part --top $info.top_module --out $vivado_dir --xdc $info.xdc
}
