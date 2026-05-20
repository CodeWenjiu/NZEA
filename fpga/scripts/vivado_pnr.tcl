# Vivado batch Tcl: read EDIF + XDC → implement → write bitstream
# Arguments are set as Tcl variables before sourcing this script.

# $::env(EDIF)     — path to EDIF netlist
# $::env(XDC)      — path to XDC constraints
# $::env(TOP)      — top module name
# $::env(PART)     — Xilinx part number (e.g. xc7a200tsbg484-1)
# $::env(BIT)      — output bitstream path

create_project -force -part $::env(PART) pnr_tmp pnr_tmp
add_files -norecurse $::env(EDIF)
add_files -fileset constrs_1 -norecurse $::env(XDC)
set_property top $::env(TOP) [current_fileset]
set_property edif_extra_search_paths [file dirname $::env(EDIF)] [current_fileset]

synth_design -top $::env(TOP) -part $::env(PART) -mode out_of_context
opt_design
place_design
route_design
write_bitstream -force $::env(BIT)

close_project
