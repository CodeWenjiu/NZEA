# Nzea Yosys synthesis (yosys-sta style)
# Env: HDL_DIR, SYNTH_DIR, DESIGN, PDK_PATH (optional, enables liberty mapping + STA)
set PLATFORM   [expr {[info exists env(PLATFORM)] ? $env(PLATFORM) : "yosys"}]
set HDL_DIR    [expr {[info exists env(HDL_DIR)] ? $env(HDL_DIR) : "build/core/$PLATFORM/riscv32i/yosys"}]
set SYNTH_DIR  [expr {[info exists env(SYNTH_DIR)] ? $env(SYNTH_DIR) : "build/core/$PLATFORM/riscv32i/yosys/synth"}]
set DESIGN     [expr {[info exists env(DESIGN)] ? $env(DESIGN) : "NzeaCore"}]
set NETLIST_V  "$SYNTH_DIR/${DESIGN}.netlist.v"
set PDK        "icsprout55"
set SCRIPT_DIR [file dirname [info script]]

# SRAM macro mapping: the design's synchronous RAMs are mapped onto the PDK macro by `memory_libmap` + a techmap
# template (both in the repo), before `synth`'s fine stage can expand a large array into flops. The macro's Verilog
# stub comes from SRAM_PATH and gives the netlist a module to instantiate.
set SRAM_MEMLIB "$SCRIPT_DIR/../sram/ics55_sram.txt"
set SRAM_MAP    "$SCRIPT_DIR/../sram/ics55_sram_map.v"
set SRAM_STUB   ""
if {[info exists env(SRAM_PATH)] && [file exists $env(SRAM_PATH)]} {
  set SRAM_STUB "$env(SRAM_PATH)/verilog/ics55_ecos_sram_8192x32_m16_stub.v"
}

# PDK: when PDK_PATH set, load liberty for tech mapping
set USE_PDK 0
if {[info exists env(PDK_PATH)] && [file exists $env(PDK_PATH)]} {
  set USE_PDK 1
  source "[file dirname [info script]]/../tcl/common.tcl"
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

synth -top $DESIGN -run :fine
# Map the collected memories onto macros now: the coarse stage collected them (`memory -nomap`) but has not expanded
# them to flops yet. A memory that fits the macro becomes a macro cell; one that does not (the predictor tables) is
# left for the fine stage to expand.
if {[file exists $SRAM_MEMLIB] && [file exists $SRAM_MAP]} {
  memory_share
  memory_collect
  memory_libmap -lib $SRAM_MEMLIB
  if {$SRAM_STUB ne "" && [file exists $SRAM_STUB]} {
    read_verilog -lib $SRAM_STUB
  }
  techmap -map $SRAM_MAP
}
share
onehot
muxpack
opt_demorgan
opt_ffinv
synth -run fine:
# Flatten before PDK mapping: ABC then optimizes across module boundaries. Without it the new back-end
# path modules (IntPath/MemPath) leave a cross-boundary tail on the report mux (WNS +0.232 -> +0.635 @500MHz).
flatten
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
  # The sweep point: `ABC_SCRIPT` overrides the mapper script (the default is the same string), so delay-vs-area
  # variants are one env var and one `just sta` apart - `just sta` re-synthesizes, so a run measures the variant end to
  # end.
  set abc_script [expr {[info exists env(ABC_SCRIPT)] ? $env(ABC_SCRIPT) : \
    "+strash;dch;map -B 0.9;topo;stime -c;buffer -c -N ${max_FO};upsize -c;dnsize -c;stime,-p;print_stats -m"}]
  abc -D "$CLK_PERIOD_PS" -constr "$sdc_file" {*}$LIBS {*}$EXCLUDE_CELLS -script $abc_script -showtmp
  hilomap -singleton -hicell {*}$TIEHI_CELL_AND_PORT -locell {*}$TIELO_CELL_AND_PORT
  setundef -zero
}

opt_clean -purge

# Generate public names for nets (full hierarchy), preferable to sequential _000019_
# splitting nets resolves unwanted compound assign statements (assign {..} = {..})
if {$USE_PDK} {
  autoname
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
