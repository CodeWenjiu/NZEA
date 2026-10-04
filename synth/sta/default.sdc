# Nzea clock constraint. Nzea Top uses "clock" port.
set CLK_PORT_NAME clock
if {[info exists env(CLK_PORT_NAME)]} {
  set CLK_PORT_NAME $::env(CLK_PORT_NAME)
}

set CLK_FREQ_MHZ 500
if {[info exists env(CLK_FREQ_MHZ)]} {
  set CLK_FREQ_MHZ $::env(CLK_FREQ_MHZ)
}

set clk_port [get_ports $CLK_PORT_NAME]
create_clock -name core_clock -period [expr 1000.0 / $CLK_FREQ_MHZ] $clk_port

# The RAM's memory runs on its own clock (the `ram_clock` port the tile shows beside `clock`). A memory macro's cycle
# time is the macro's own — this one needs 3.03 ns — so clocking it from the core would cap the core at the macro's
# frequency. Declaring the two clocks unrelated keeps the macro's access path out of the core_clock report (the two-phase
# handshake in RamFabricSlave is the crossing's guarantee), and the macro's own paths are checked against `ram_clock`.
set SRAM_CLK_PORT_NAME ram_clock
if {[info exists env(SRAM_CLK_PORT_NAME)]} {
  set SRAM_CLK_PORT_NAME $::env(SRAM_CLK_PORT_NAME)
}

set SRAM_CLK_PERIOD_NS 4.0
if {[info exists env(SRAM_CLK_PERIOD_NS)]} {
  set SRAM_CLK_PERIOD_NS $::env(SRAM_CLK_PERIOD_NS)
}

# Only the tile has a memory clock; the core target's netlist has no such port, so the constraint is added only when
# the port exists.
if {![catch {get_ports $SRAM_CLK_PORT_NAME} sram_clk_ports] && [llength $sram_clk_ports] > 0} {
  create_clock -name ram_clock -period $SRAM_CLK_PERIOD_NS $sram_clk_ports
  set_clock_groups -asynchronous -group {core_clock} -group {ram_clock}
}
