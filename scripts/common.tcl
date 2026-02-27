# Common for PDK/STA. Sources PDK config.
set SCRIPT_DIR [file dirname [info script]]
set PROJ_HOME [file dirname $SCRIPT_DIR]
if {![info exists PDK]} { set PDK "icsprout55" }
source "$SCRIPT_DIR/pdk/$PDK.tcl"
