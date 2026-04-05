# iEDA STA script for Nzea. Args: SDC_FILE NETLIST_V DESIGN PDK
set SDC_FILE   [lindex $argv 0]
set NETLIST_V  [lindex $argv 1]
set DESIGN     [lindex $argv 2]
set PDK        [lindex $argv 3]
set RESULT_DIR [file dirname $NETLIST_V]

source "[file dirname [info script]]/common.tcl"

set_design_workspace $RESULT_DIR
read_netlist $NETLIST_V
read_liberty [concat $LIB_FILES]
link_design $DESIGN
read_sdc  $SDC_FILE
# Timing report (e.g. Top.rpt under $RESULT_DIR) — stop here: full toggle power analysis
# has been observed to overload the host *after* timing completes.
report_timing -max_path 5
