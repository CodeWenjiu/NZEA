# Nzea Yosys synthesis (yosys-sta style)
# Env: HDL_DIR, SYNTH_DIR, DESIGN, PDK_PATH (optional, enables liberty mapping + STA)
set HDL_DIR    [expr {[info exists env(HDL_DIR)] ? $env(HDL_DIR) : "build/yosys"}]
set SYNTH_DIR  [expr {[info exists env(SYNTH_DIR)] ? $env(SYNTH_DIR) : "build/synth"}]
set DESIGN     [expr {[info exists env(DESIGN)] ? $env(DESIGN) : "Top"}]
set NETLIST_V  "$SYNTH_DIR/${DESIGN}.netlist.v"
set PDK        "icsprout55"

# PDK: when PDK_PATH set, load liberty for tech mapping
set USE_PDK 0
if {[info exists env(PDK_PATH)] && [file exists $env(PDK_PATH)]} {
  set USE_PDK 1
  source "[file dirname [info script]]/common.tcl"
  set LIBS [concat {*}[lmap lib $LIB_FILES {concat "-liberty" $lib}]]
  set EXCLUDE_CELLS [concat {*}[lmap cell $DONT_USE_CELLS {concat "-dont_use" $cell}]]
  set CLK_FREQ_MHZ [expr {[info exists env(CLK_FREQ_MHZ)] ? $env(CLK_FREQ_MHZ) : 500}]
  set CLK_PERIOD_PS [expr {1000.0 * 1000.0 / $CLK_FREQ_MHZ}]
  set sdc_file $SYNTH_DIR/abc.sdc
  set outfile [open $sdc_file w]
  puts $outfile "set_driving_cell $BUF_CELL"
  puts $outfile "set_load 1.6"
  close $outfile
}

# Build file list from filelist.f. Top = Core with ibus/dbus/commit exposed as top-level ports
set VERILOG_FILES {}
if {[file exists "$HDL_DIR/filelist.f"]} {
  set f [open "$HDL_DIR/filelist.f" r]
  while {[gets $f line] >= 0} {
    set line [string trim $line]
    if {$line eq ""} continue
    if {[file exists "$HDL_DIR/$line"]} {
      lappend VERILOG_FILES "$HDL_DIR/$line"
    }
  }
  close $f
} else {
  foreach f [glob -nocomplain $HDL_DIR/*.sv] {
    lappend VERILOG_FILES $f
  }
}

if {[llength $VERILOG_FILES] == 0} {
  puts stderr "Error: No Verilog files in $HDL_DIR"
  exit 1
}

#===========================================================
#   main running
#===========================================================
yosys -import

foreach file $VERILOG_FILES { read_verilog -sv $file }
hierarchy -check -top $DESIGN
# Top's ibus_resp_*, dbus_resp_* are primary inputs; logic preserved

synth -top $DESIGN -flatten -run :fine
share -aggressive
onehot
muxpack
opt_demorgan
opt_ffinv
synth -run fine:
opt_clean -purge

if {$USE_PDK} {
  splitnets -format __v
  yosys rename -wire -suffix _reg_p t:*DFF*_P*
  yosys rename -wire -suffix _reg_n t:*DFF*_N*
  autoname t:*DFF* %n
  clockgate {*}$LIBS {*}$EXCLUDE_CELLS
  dfflibmap {*}$LIBS {*}$EXCLUDE_CELLS
  opt -undriven -purge
  set max_FO 24
  set abc_script "+strash;dch;map -B 0.9;topo;stime -c;buffer -c -N ${max_FO};upsize -c;dnsize -c;stime,-p;print_stats -m"
  abc -D "$CLK_PERIOD_PS" -constr "$sdc_file" {*}$LIBS {*}$EXCLUDE_CELLS -script $abc_script -showtmp
  hilomap -singleton -hicell {*}$TIEHI_CELL_AND_PORT -locell {*}$TIELO_CELL_AND_PORT
  setundef -zero
}

opt_clean -purge

# splitting nets resolves unwanted compound assign statements in netlist (assign {..} = {..})
# required for iEDA STA compatibility (from yosys-sta)
if {$USE_PDK} {
  splitnets -format __v -ports
  opt_clean -purge
}

# reports
if {$USE_PDK} {
  foreach l $LIB_FILES { read_liberty -lib $l }
  tee -o $SYNTH_DIR/synth_check.txt check -mapped
  tee -o $SYNTH_DIR/synth_stat.txt stat {*}$LIBS
} else {
  tee -o $SYNTH_DIR/synth_check.txt check
  tee -o $SYNTH_DIR/synth_stat.txt stat -tech cmos
}

write_verilog -noattr -noexpr -nohex -nodec $NETLIST_V
