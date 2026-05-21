# Vivado project setup — source this in Vivado Tcl Console to recreate the project
#   1. Open Vivado (no project)
#   2. Tcl Console: cd .../fpga/lxb_artix7/vivado
#   3. source create_project.tcl

create_project -force nzea_a7lite ./nzea_a7lite -part xc7a200tsbg484-1
add_files -norecurse ../top.v
add_files -fileset constrs_1 -norecurse ../A7_lite.xdc
set_property top top [current_fileset]
update_compile_order -fileset sources_1
update_compile_order -fileset sim_1